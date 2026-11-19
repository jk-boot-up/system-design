import { createRemoteJWKSet, jwtVerify } from 'jose';
import type { Route } from './+types/backchannel-logout';
import { runtime } from '../../.server/runtime.ts';

/**
 * OpenID Connect back-channel logout: Keycloak POSTs a signed logout token when a session ends anywhere (another device,
 * an admin, a password change). The token is verified (signature, issuer, audience, event) and every storefront session
 * of that Keycloak session is deleted.
 */
let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

export async function action({ request }: Route.ActionArgs) {
  const rt = runtime();
  const form = await request.formData();
  const token = form.get('logout_token');
  if (typeof token !== 'string') return new Response(null, { status: 400 });
  const config = await rt.oidc.config();
  const meta = config.serverMetadata();
  if (!meta.jwks_uri) return new Response(null, { status: 501 });
  let jwksUri = new URL(meta.jwks_uri);
  if (rt.config.OIDC_BACKCHANNEL_ORIGIN && jwksUri.origin === new URL(rt.config.OIDC_ISSUER).origin) {
    jwksUri = new URL(jwksUri.pathname, rt.config.OIDC_BACKCHANNEL_ORIGIN);
  }
  jwks ??= createRemoteJWKSet(jwksUri);
  try {
    const { payload } = await jwtVerify(token, jwks, { issuer: rt.config.OIDC_ISSUER, audience: rt.config.OIDC_CLIENT_ID, maxTokenAge: '2m' });
    const events = payload.events as Record<string, unknown> | undefined;
    if (!events || !('http://schemas.openid.net/event/backchannel-logout' in events) || 'nonce' in payload) {
      return new Response(null, { status: 400 });
    }
    const sid = typeof payload.sid === 'string' ? payload.sid : undefined;
    if (!sid) return new Response(null, { status: 400 });
    const ended = await rt.sessions.destroyByKeycloakSession(sid);
    rt.log.info({ ended }, 'back-channel logout');
    return new Response(null, { status: 200, headers: { 'Cache-Control': 'no-store' } });
  } catch (e) {
    rt.log.warn({ err: e instanceof Error ? e.message : String(e) }, 'invalid logout token');
    return new Response(null, { status: 400 });
  }
}
