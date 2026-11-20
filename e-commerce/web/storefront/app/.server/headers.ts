import { randomBytes } from 'node:crypto';

/**
 * The page Content-Security-Policy of design/16 §16.6 (the other security headers are set by server.ts for every
 * response). It carries a fresh nonce per page: only scripts and
 * styles the server rendered with that nonce run. In development Vite injects styles and connects a WebSocket for hot
 * reload, so the policy is relaxed there only.
 */
export const newNonce = (): string => randomBytes(18).toString('base64');

export function contentSecurityPolicy(nonce: string, opts: { dev: boolean; imageOrigin: string; idOrigin: string }): string {
  const directives = [
    "default-src 'self'",
    `script-src 'nonce-${nonce}' 'strict-dynamic'`,
    opts.dev ? "style-src 'self' 'unsafe-inline'" : `style-src 'self' 'nonce-${nonce}'`,
    "style-src-attr 'unsafe-inline'",
    `img-src 'self' ${opts.imageOrigin} data:`,
    opts.dev ? "connect-src 'self' ws: wss:" : "connect-src 'self'",
    "font-src 'self'",
    "frame-ancestors 'none'",
    `form-action 'self' ${opts.idOrigin}`,
    "base-uri 'none'",
    "object-src 'none'",
  ];
  // Trusted Types, enforced: no string reaches a script sink (innerHTML, eval, script src or text, …) and no page may
  // create a policy that would let one through. React escapes everything it renders and the storefront has no raw-HTML
  // rendering (lint bans it), so nothing needs a policy; the browser journeys fail on any violation.
  if (!opts.dev) directives.push("require-trusted-types-for 'script'", "trusted-types 'none'", 'upgrade-insecure-requests');
  return directives.join('; ');
}
