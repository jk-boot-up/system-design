import { redirect } from 'react-router';
import type { Route } from './+types/logout';
import { runtime } from '../../.server/runtime.ts';
import { shop } from '../../.server/shop.ts';

/** Sign-out is a POST with the CSRF token (a link could be triggered by another site). GET just goes home. */
export function loader() {
  throw redirect('/');
}

export async function action({ context }: Route.ActionArgs) {
  const rt = runtime();
  const s = shop(context);
  const idToken = s.session?.data.tokens?.idToken;
  if (s.session) await rt.sessions.destroy(s.session);
  // End the Keycloak session too (RP-initiated logout); Keycloak sends the browser back to the shop.
  const url = await rt.oidc.signOutUrl(idToken).catch(() => undefined);
  throw redirect(url?.href ?? '/', { headers: { 'Cache-Control': 'no-store' } });
}
