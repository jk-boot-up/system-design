import { redirect } from 'react-router';
import type { Route } from './+types/login';
import { runtime } from '../../.server/runtime.ts';
import { safeReturnTo, shop } from '../../.server/shop.ts';

/**
 * Starts sign-in: a fresh state, nonce and PKCE verifier are kept in the session (never in the browser) and the shopper
 * is sent to Keycloak. `returnTo` is limited to our own paths (no open redirect).
 */
export async function loader({ request, context }: Route.LoaderArgs) {
  const s = shop(context);
  const returnTo = safeReturnTo(new URL(request.url).searchParams.get('returnTo'));
  if (s.user) throw redirect(returnTo);
  const { url, state, nonce, verifier } = await runtime().oidc.startSignIn();
  const session = s.ensureSession();
  session.data.login = { state, nonce, verifier, returnTo, startedAt: Date.now() };
  session.dirty = true;
  throw redirect(url.href, { headers: { 'Cache-Control': 'no-store' } });
}
