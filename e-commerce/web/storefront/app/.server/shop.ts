import { randomUUID } from 'node:crypto';
import { createContext, data, redirect, type MiddlewareFunction, type RouterContextProvider } from 'react-router';
import { safeEqual } from './crypto.ts';
import { gateway, type Gateway } from './gateway.ts';
import { runtime } from './runtime.ts';
import { SESSION_COOKIE, sessionCookie, type Session, type User } from './session.ts';

/**
 * Per-request state shared by every loader and action: the session, the signed-in user, and a gateway client that
 * carries this shopper's token. Built by `shopMiddleware`, which also enforces the CSRF rules and writes the session and
 * cookies back after the route has run (design/16 §16.5, §16.6).
 */
export interface Shop {
  requestId: string;
  user?: User;
  /** CSRF token for forms; only signed-in pages carry one (anonymous pages may be cached by the edge). */
  csrf?: string;
  api: Gateway;
  session?: Session;
  /** Returns the session, creating one if needed (sign-in start, flash messages). */
  ensureSession(): Session;
  /** The guest cart id, creating the cookie if asked. */
  guestId(create: boolean): string | undefined;
  /** Ends the session and forgets the guest cart cookie. */
  clearGuest(): void;
}

export const shopContext = createContext<Shop>();

export const GUEST_COOKIE = '__Host-guest';
const GUEST_MAX_AGE = 30 * 24 * 60 * 60;
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REQUEST_ID = /^[A-Za-z0-9-]{8,64}$/;
/** Paths that receive server-to-server POSTs, authenticated by other means (a signed logout token). */
const CSRF_EXEMPT = new Set(['/auth/backchannel-logout']);
const REFRESH_BEFORE_MS = 60_000;

export function shop(context: Readonly<RouterContextProvider>): Shop {
  return context.get(shopContext);
}

export const shopMiddleware: MiddlewareFunction<Response> = async ({ request, context }, next) => {
  const rt = runtime();
  const cookies = parseCookies(request.headers.get('Cookie'));
  const incomingId = request.headers.get('X-Request-Id');
  const requestId = incomingId && REQUEST_ID.test(incomingId) ? incomingId : randomUUID();
  // The edge sets X-Real-IP; only the edge can reach the storefront (network policy), so it is trusted.
  const clientIp = request.headers.get('X-Real-IP') ?? undefined;

  let session = await rt.sessions.load(cookies[SESSION_COOKIE]);
  const presentedSessionCookie = cookies[SESSION_COOKIE] !== undefined;

  const url = new URL(request.url);
  if (!SAFE_METHODS.has(request.method) && !CSRF_EXEMPT.has(url.pathname.replace(/\.data$/, ''))) {
    await verifyCsrf(request, session);
  }

  if (session?.data.tokens && session.data.user) session = await refreshIfDue(session);

  let guest = cookies[GUEST_COOKIE] && UUID.test(cookies[GUEST_COOKIE]) ? cookies[GUEST_COOKIE] : undefined;
  let guestCookie: string | undefined;

  const state: Shop = {
    requestId,
    user: session?.data.user,
    csrf: session?.data.user ? session.data.csrf : undefined,
    session,
    api: gateway(rt.config.GATEWAY_URL, {
      token: session?.data.user ? session.data.tokens?.access : undefined,
      guestId: session?.data.user ? undefined : guest,
      requestId,
      clientIp,
    }),
    ensureSession() {
      state.session ??= rt.sessions.create();
      return state.session;
    },
    guestId(create) {
      if (!guest && create) {
        guest = randomUUID();
        guestCookie = `${GUEST_COOKIE}=${guest}; Path=/; Max-Age=${GUEST_MAX_AGE}; HttpOnly; Secure; SameSite=Lax`;
        state.api = gateway(rt.config.GATEWAY_URL, { requestId, clientIp, guestId: guest });
      }
      return guest;
    },
    clearGuest() {
      if (cookies[GUEST_COOKIE]) guestCookie = `${GUEST_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax`;
      guest = undefined;
    },
  };
  context.set(shopContext, state);

  const response = await next();

  const s = state.session;
  if (s?.dirty) await rt.sessions.save(s);
  if (s && (s.rotated || s.destroyed)) response.headers.append('Set-Cookie', sessionCookie(s));
  else if (!s && presentedSessionCookie) response.headers.append('Set-Cookie', `${SESSION_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax`);
  if (guestCookie) response.headers.append('Set-Cookie', guestCookie);
  // Anything that depends on who is asking must never be stored by the edge, a CDN or the browser cache.
  if (s || guest || guestCookie) {
    response.headers.set('Cache-Control', 'private, no-store');
    response.headers.delete('X-Accel-Expires');
  } else if (response.headers.get('Content-Type')?.startsWith('text/html')) {
    // An HTML page carries a one-time CSP nonce: a shared copy would hand everyone the same nonce. Only the loader data
    // behind client-side navigation (`.data`, no nonce) and the assets are cached at the edge.
    response.headers.set('Cache-Control', 'private, no-cache');
    response.headers.delete('X-Accel-Expires');
  }
  response.headers.set('X-Request-Id', requestId);
  return response;
};

/**
 * CSRF (design/16 §16.6). Every state-changing request must come from our own pages: `Origin` (or, when a browser omits
 * it, `Sec-Fetch-Site`) must match. Signed-in sessions must also send their per-session token. SameSite=Lax cookies are
 * the third layer.
 */
async function verifyCsrf(request: Request, session: Session | undefined): Promise<void> {
  const expected = runtime().config.PUBLIC_ORIGIN;
  const origin = request.headers.get('Origin');
  const site = request.headers.get('Sec-Fetch-Site');
  const sameOrigin = origin ? origin === expected : site === 'same-origin';
  if (!sameOrigin) throw data('Cross-site request refused', { status: 403 });
  if (!session?.data.user) return;
  let token = request.headers.get('X-CSRF-Token') ?? undefined;
  if (!token && /form-urlencoded|multipart\/form-data/.test(request.headers.get('Content-Type') ?? '')) {
    const value = (await request.clone().formData()).get('_csrf');
    token = typeof value === 'string' ? value : undefined;
  }
  if (!token || !safeEqual(token, session.data.csrf)) throw data('Missing or invalid form token; reload the page', { status: 403 });
}

/** Refreshes the access token a minute before it expires. A failed refresh signs the shopper out locally. */
async function refreshIfDue(session: Session): Promise<Session> {
  const rt = runtime();
  const tokens = session.data.tokens!;
  if (tokens.expiresAt - Date.now() > REFRESH_BEFORE_MS) return session;
  // One refresh per session at a time: refresh tokens rotate, so a second concurrent refresh would fail.
  const lock = await rt.redis.set(`storefront:refresh:${session.id.slice(0, 16)}`, '1', { condition: 'NX', expiration: { type: 'PX', value: 5_000 } });
  if (lock !== 'OK') return session;
  try {
    const { user, tokens: next } = await rt.oidc.refresh(tokens);
    const rolesChanged = user.roles.join(',') !== session.data.user!.roles.join(',');
    session.data.user = { ...user, amr: session.data.user!.amr, authTime: session.data.user!.authTime };
    session.data.tokens = next;
    session.dirty = true;
    if (rolesChanged) await rt.sessions.rotate(session);
  } catch (e) {
    rt.log.info({ err: e instanceof Error ? e.message : String(e) }, 'token refresh failed; signing out locally');
    delete session.data.user;
    delete session.data.tokens;
    session.data.flash = 'Your session has ended. Please sign in again.';
    session.dirty = true;
  }
  return session;
}

/** Sends a signed-out visitor to sign in, returning to this page afterwards. */
export function requireUser(context: Readonly<RouterContextProvider>, request: Request, role?: string): User {
  const { user } = shop(context);
  if (!user) {
    const here = new URL(request.url);
    throw redirect(`/login?returnTo=${encodeURIComponent(here.pathname.replace(/\.data$/, '') + here.search)}`);
  }
  if (role && !user.roles.includes(role)) throw data('You do not have access to this page', { status: 403 });
  return user;
}

export function parseCookies(header: string | null): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (header ?? '').split(';')) {
    const i = part.indexOf('=');
    if (i <= 0) continue;
    const name = part.slice(0, i).trim();
    if (!(name in out)) out[name] = part.slice(i + 1).trim();
  }
  return out;
}

/** `returnTo` must be a path on our own origin: no scheme, no host, no protocol-relative `//`, no backslashes. */
export function safeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\') || /[\r\n]/.test(value)) return '/';
  try {
    const u = new URL(value, 'https://storefront.invalid');
    return u.origin === 'https://storefront.invalid' ? u.pathname + u.search : '/';
  } catch {
    return '/';
  }
}
