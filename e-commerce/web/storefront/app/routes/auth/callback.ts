import { data, redirect } from 'react-router';
import type { Route } from './+types/callback';
import { call, gateway } from '../../.server/gateway.ts';
import { runtime } from '../../.server/runtime.ts';
import { shop } from '../../.server/shop.ts';

const LOGIN_TTL_MS = 10 * 60_000;

/**
 * Keycloak returns here. The code is exchanged with the client secret and PKCE verifier; state and nonce are checked.
 * Then the session gets a new id (against fixation), and a guest cart is merged into the account cart.
 */
export async function loader({ request, context }: Route.LoaderArgs) {
  const rt = runtime();
  const s = shop(context);
  const login = s.session?.data.login;
  const url = new URL(request.url);
  if (!s.session || !login || Date.now() - login.startedAt > LOGIN_TTL_MS) throw redirect('/login');
  if (url.searchParams.has('error')) {
    delete s.session.data.login;
    s.session.data.flash = 'Sign-in was cancelled.';
    s.session.dirty = true;
    throw redirect(login.returnTo);
  }
  // Build the callback URL from our public origin: behind the edge the request URL is http://storefront:8090/…
  const callbackUrl = new URL(`${rt.config.PUBLIC_ORIGIN}${url.pathname}${url.search}`);
  let result;
  try {
    result = await rt.oidc.finishSignIn(callbackUrl, login);
  } catch (e) {
    rt.log.warn({ err: e instanceof Error ? e.message : String(e), requestId: s.requestId }, 'sign-in failed');
    throw data('Sign-in failed. Please try again.', { status: 400 });
  }
  delete s.session.data.login;
  s.session.data.user = result.user;
  s.session.data.tokens = result.tokens;
  await rt.sessions.rotate(s.session);

  const guest = s.guestId(false);
  if (guest) {
    const api = gateway(rt.config.GATEWAY_URL, { token: result.tokens.access, requestId: s.requestId });
    const merged = await call(api.cart.POST('/api/v1/cart/merge', { body: { guestId: guest } })).then(() => true, () => false);
    if (merged) s.clearGuest();
    else rt.log.warn({ requestId: s.requestId }, 'guest cart merge failed; kept for the next sign-in');
  }
  throw redirect(login.returnTo, { headers: { 'Cache-Control': 'no-store' } });
}
