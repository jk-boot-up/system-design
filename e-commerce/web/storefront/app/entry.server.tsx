import { PassThrough } from 'node:stream';
import { createReadableStreamFromReadable } from '@react-router/node';
import { isbot } from 'isbot';
import { renderToPipeableStream } from 'react-dom/server';
import { ServerRouter, type EntryContext, type HandleErrorFunction, type RouterContextProvider } from 'react-router';
import { contentSecurityPolicy, newNonce } from './.server/headers.ts';
import { runtime } from './.server/runtime.ts';
import { ApiError } from './lib/problem.ts';

// server.ts reaches readiness and shutdown through the server build.
export { ready, shutdown } from './.server/runtime.ts';

export const streamTimeout = 5_000;

/** Renders a page as a stream, with a per-page CSP nonce on every script React Router emits. */
export default function handleRequest(
  request: Request,
  status: number,
  headers: Headers,
  routerContext: EntryContext,
  _loadContext: RouterContextProvider,
): Promise<Response> | Response {
  if (request.method.toUpperCase() === 'HEAD') return new Response(null, { status, headers });
  const rt = runtime();
  const nonce = newNonce();
  headers.set('Content-Security-Policy', contentSecurityPolicy(nonce, {
    dev: import.meta.env.DEV,
    imageOrigin: new URL(rt.config.IMAGE_BASE_URL).origin,
    idOrigin: new URL(rt.config.OIDC_ISSUER).origin,
  }));
  const userAgent = request.headers.get('user-agent');
  const waitForAll = (userAgent && isbot(userAgent)) || routerContext.isSpaMode;

  return new Promise((resolve, reject) => {
    let shellRendered = false;
    let timeout: ReturnType<typeof setTimeout> | undefined = setTimeout(() => abort(), streamTimeout + 1000);
    const { pipe, abort } = renderToPipeableStream(<ServerRouter context={routerContext} url={request.url} nonce={nonce} />, {
      nonce,
      [waitForAll ? 'onAllReady' : 'onShellReady']() {
        shellRendered = true;
        const body = new PassThrough({
          final(callback) {
            clearTimeout(timeout);
            timeout = undefined;
            callback();
          },
        });
        headers.set('Content-Type', 'text/html; charset=utf-8');
        pipe(body);
        resolve(new Response(createReadableStreamFromReadable(body), { headers, status }));
      },
      onShellError: reject,
      onError(error: unknown) {
        status = 500;
        if (shellRendered) rt.log.error({ err: error instanceof Error ? error.message : String(error) }, 'render error');
      },
    });
  });
}

/** Unexpected loader, action and render errors: logged once with the request id, never shown to shoppers. */
export const handleError: HandleErrorFunction = (error, { request }) => {
  if (request.signal.aborted) return;
  // Refused cross-site actions are expected noise, not faults: one line, no stack.
  if (error instanceof Error && /origin|Bad Request/.test(error.message) && request.method !== 'GET') {
    runtime().log.info({ path: new URL(request.url).pathname, origin: request.headers.get('Origin') }, 'cross-site action refused');
    return;
  }
  if (error instanceof ApiError) {
    const level = error.status >= 500 ? 'warn' : 'info';
    runtime().log[level]({ status: error.status, code: error.code, traceId: error.problem.traceId, path: new URL(request.url).pathname }, 'API error reached a page');
    return;
  }
  const err = error instanceof Error ? { name: error.name, message: error.message, stack: error.stack } : { message: String(error) };
  runtime().log.error({ err, path: new URL(request.url).pathname, requestId: request.headers.get('X-Request-Id') }, 'unhandled error');
};
