import * as client from 'openid-client';
import type { Config } from './config.ts';
import type { Tokens, User } from './session.ts';

/**
 * OpenID Connect as a confidential client (design/16 §16.5): authorization code with PKCE and nonce, client secret at
 * the token endpoint, refresh on the server, RP-initiated logout. Tokens never reach the browser.
 */
export class Oidc {
  private configuration?: Promise<client.Configuration>;
  private readonly cfg: Config;

  constructor(cfg: Config) {
    this.cfg = cfg;
  }

  /** Discovery once, retried on the next call if it failed (Keycloak may start after us). */
  config(): Promise<client.Configuration> {
    this.configuration ??= this.discover().catch((e: unknown) => {
      this.configuration = undefined;
      throw e;
    });
    return this.configuration;
  }

  private async discover(): Promise<client.Configuration> {
    const { OIDC_ISSUER, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, OIDC_ALLOW_HTTP, OIDC_BACKCHANNEL_ORIGIN } = this.cfg;
    const issuerOrigin = new URL(OIDC_ISSUER).origin;
    const options: client.DiscoveryRequestOptions = { timeout: 5 };
    if (OIDC_ALLOW_HTTP) options.execute = [client.allowInsecureRequests];
    if (OIDC_BACKCHANNEL_ORIGIN) {
      // The issuer's public host is not reachable from inside: send server-to-server calls to the in-cluster origin.
      const inside = new URL(OIDC_BACKCHANNEL_ORIGIN).origin;
      options[client.customFetch] = (url, init) =>
        fetch(url.startsWith(issuerOrigin) ? inside + url.slice(issuerOrigin.length) : url, init as RequestInit);
    }
    return client.discovery(new URL(OIDC_ISSUER), OIDC_CLIENT_ID, { id_token_signed_response_alg: 'RS256' },
      client.ClientSecretBasic(OIDC_CLIENT_SECRET), options);
  }

  redirectUri(): string {
    return `${this.cfg.PUBLIC_ORIGIN}/auth/callback`;
  }

  async startSignIn(): Promise<{ url: URL; state: string; nonce: string; verifier: string }> {
    const config = await this.config();
    const verifier = client.randomPKCECodeVerifier();
    const state = client.randomState();
    const nonce = client.randomNonce();
    const url = client.buildAuthorizationUrl(config, {
      redirect_uri: this.redirectUri(),
      scope: 'openid profile email',
      code_challenge: await client.calculatePKCECodeChallenge(verifier),
      code_challenge_method: 'S256',
      state,
      nonce,
    });
    return { url, state, nonce, verifier };
  }

  /** Exchanges the code; checks state, nonce, PKCE and the ID token's signature, issuer, audience and expiry. */
  async finishSignIn(callbackUrl: URL, login: { state: string; nonce: string; verifier: string }): Promise<{ user: User; tokens: Tokens }> {
    const config = await this.config();
    const response = await client.authorizationCodeGrant(config, callbackUrl, {
      pkceCodeVerifier: login.verifier,
      expectedState: login.state,
      expectedNonce: login.nonce,
      idTokenExpected: true,
    }, { redirect_uri: this.redirectUri() });
    return toSession(response);
  }

  async refresh(tokens: Tokens): Promise<{ user: User; tokens: Tokens }> {
    if (!tokens.refresh) throw new Error('no refresh token');
    const response = await client.refreshTokenGrant(await this.config(), tokens.refresh);
    const next = toSession(response, tokens);
    return next;
  }

  /** Where to send the browser to end the Keycloak session too. */
  async signOutUrl(idToken: string | undefined): Promise<URL> {
    const params: Record<string, string> = { post_logout_redirect_uri: `${this.cfg.PUBLIC_ORIGIN}/`, client_id: this.cfg.OIDC_CLIENT_ID };
    if (idToken) params.id_token_hint = idToken;
    return client.buildEndSessionUrl(await this.config(), params);
  }
}

type TokenResponse = client.TokenEndpointResponse & client.TokenEndpointResponseHelpers;

function toSession(response: TokenResponse, previous?: Tokens): { user: User; tokens: Tokens } {
  const id = response.claims();
  // Roles come from the access token (the realm puts them there for the API); it was received straight from the token
  // endpoint over TLS with client authentication, and the gateway verifies it again on every call.
  const access = decodePayload(response.access_token);
  const roles = Array.isArray(access.roles) ? access.roles.filter((r): r is string => typeof r === 'string') : [];
  const amr = Array.isArray(access.amr) ? access.amr.filter((r): r is string => typeof r === 'string') : [];
  const claims = id ?? access;
  const user: User = {
    sub: String(claims.sub ?? access.sub),
    name: String(claims.given_name ?? claims.name ?? claims.preferred_username ?? 'Customer'),
    email: typeof claims.email === 'string' ? claims.email : undefined,
    roles,
    amr,
    authTime: Number(claims.auth_time ?? access.auth_time ?? Math.floor(Date.now() / 1000)),
  };
  const tokens: Tokens = {
    access: response.access_token,
    refresh: response.refresh_token ?? previous?.refresh,
    idToken: response.id_token ?? previous?.idToken,
    expiresAt: Date.now() + (response.expires_in ?? 300) * 1000,
    sid: typeof access.sid === 'string' ? access.sid : previous?.sid,
  };
  return { user, tokens };
}

export function decodePayload(jwt: string): Record<string, unknown> {
  const part = jwt.split('.')[1];
  if (!part) return {};
  try {
    return JSON.parse(Buffer.from(part, 'base64url').toString('utf8')) as Record<string, unknown>;
  } catch {
    return {};
  }
}
