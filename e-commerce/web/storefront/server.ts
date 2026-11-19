// The storefront's HTTP server (design/16 §16.12): Express 5 in front of React Router. Run directly by Node 26 (type
// stripping), so there is no separate server build. Pages, loaders and actions are the React Router build; this file only
// adds what a production server needs around them: static assets with long-lived caching, security headers, probes,
// metrics, a request size limit and a graceful shutdown that drains in-flight requests.
import { flushTraces } from './tracing.ts';   // first: instruments Node's HTTP server before Express loads
import { collectDefaultMetrics, Histogram, Registry } from '@prometheus-io/client';
import { createRequestHandler } from '@react-router/express';
import compression from 'compression';
import express, { type NextFunction, type Request, type Response } from 'express';
import type { ServerBuild } from 'react-router';

const PRODUCTION = process.env.NODE_ENV === 'production';
const PORT = Number(process.env.PORT ?? 8090);
const MAX_BODY_BYTES = 6 * 1024 * 1024;   // a 5 MB image plus form overhead
const DRAIN_MS = 20_000;

/** Readiness and shutdown are exported by app/entry.server.tsx. */
interface Lifecycle {
  ready(): Promise<boolean>;
  shutdown(): Promise<void>;
}

const app = express();
app.disable('x-powered-by');
// Exactly one proxy (the edge) is in front: its X-Forwarded-Proto makes request URLs https, so React Router's own
// Origin check and ours agree with the browser. Only the edge can reach this port (network policy).
app.set('trust proxy', 1);

// ── metrics ───────────────────────────────────────────────────────────────────────────────────────────────────────────
const registry = new Registry();
collectDefaultMetrics({ register: registry });
const duration = new Histogram({
  name: 'http_server_requests_seconds',
  help: 'Storefront request duration',
  labelNames: ['method', 'status', 'kind'] as const,
  buckets: [0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10],
  registers: [registry],
});
app.use((req, res, next) => {
  const end = duration.startTimer();
  const kind = req.path.startsWith('/assets/') ? 'asset' : req.path.endsWith('.data') ? 'data' : 'page';
  res.on('finish', () => end({ method: req.method, status: `${Math.floor(res.statusCode / 100)}xx`, kind }));
  next();
});

// ── headers and limits ────────────────────────────────────────────────────────────────────────────────────────────────
const SECURITY_HEADERS: Record<string, string> = {
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains; preload',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-site',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()',
};
app.use((req, res, next) => {
  res.set(SECURITY_HEADERS);
  const length = Number(req.headers['content-length'] ?? 0);
  if (length > MAX_BODY_BYTES) {
    res.status(413).type('text/plain').send('Request too large');
    return;
  }
  next();
});
app.use(compression());

// ── probes and metrics (not routed by the edge) ───────────────────────────────────────────────────────────────────────
let draining = false;
let lifecycle: Lifecycle | undefined;
app.get('/healthz', (_req, res) => { res.type('text/plain').send('ok'); });
app.get('/readyz', async (_req, res) => {
  const ok = !draining && lifecycle !== undefined && (await lifecycle.ready());
  res.status(ok ? 200 : 503).type('text/plain').send(ok ? 'ready' : 'not ready');
});
app.get('/metrics', async (_req, res) => {
  res.type(registry.contentType).send(await registry.metrics());
});

// ── the app ───────────────────────────────────────────────────────────────────────────────────────────────────────────
if (PRODUCTION) {
  const buildPath = './build/server/index.js';
  const build = (await import(buildPath)) as ServerBuild;
  lifecycle = build.entry.module as unknown as Lifecycle;
  // Source maps stay on the server for error reports; browsers never get them.
  app.use((req, res, next) => (req.path.endsWith('.map') ? res.status(404).end() : next()));
  // Hashed file names: cached for a year, never revalidated. Old files stay in the image for one release (§16.12).
  app.use('/assets', express.static('build/client/assets', { immutable: true, maxAge: '1y', index: false, fallthrough: false }));
  app.use(express.static('build/client', { maxAge: '1h', index: false }));
  app.all('/{*path}', createRequestHandler({ build, mode: 'production' }));
} else {
  const vite = await import('vite');
  const server = await vite.createServer({ server: { middlewareMode: true } });
  app.use(server.middlewares);
  const load = async () => (await server.ssrLoadModule('virtual:react-router/server-build')) as ServerBuild;
  const module = async () => (await load()).entry.module as unknown as Lifecycle;
  lifecycle = { ready: async () => (await module()).ready(), shutdown: async () => (await module()).shutdown() };
  app.all('/{*path}', createRequestHandler({ build: load, mode: 'development' }));
}

// Anything that escaped React Router (it handles its own errors): no stack traces to the client.
app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  console.error(JSON.stringify({ level: 'error', msg: 'unhandled server error', err: err instanceof Error ? err.message : String(err) }));
  if (!res.headersSent) res.status(500).type('text/plain').send('Something went wrong');
});

const server = app.listen(PORT, () => {
  console.log(JSON.stringify({ level: 'info', msg: `storefront listening on ${PORT}`, mode: PRODUCTION ? 'production' : 'development' }));
});
server.keepAliveTimeout = 65_000;   // longer than the edge's upstream keep-alive
server.headersTimeout = 66_000;
server.requestTimeout = 30_000;

/** SIGTERM: fail readiness so no new traffic arrives, finish what is in flight, then close Redis and exit. */
function shutdown(signal: string): void {
  if (draining) return;
  draining = true;
  console.log(JSON.stringify({ level: 'info', msg: `${signal}: draining` }));
  const force = setTimeout(() => process.exit(1), DRAIN_MS);
  force.unref();
  server.close(async () => {
    await lifecycle?.shutdown().catch(() => undefined);
    await flushTraces();
    process.exit(0);
  });
  server.closeIdleConnections();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
