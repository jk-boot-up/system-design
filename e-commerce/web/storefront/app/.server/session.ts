import { createClient } from 'redis';
import { open, parseKeyring, randomId, seal, storageKey, type Keyring } from './crypto.ts';

/**
 * Server-side sessions in `redis-session` (design/16 §16.5). The browser holds only a random id in `__Host-sid`; the
 * tokens and everything else stay here, sealed with AES-256-GCM. Idle timeout 30 minutes, absolute 12 hours.
 */
export const SESSION_COOKIE = '__Host-sid';
export const IDLE_SECONDS = 30 * 60;
export const ABSOLUTE_SECONDS = 12 * 60 * 60;
const TOUCH_AFTER_MS = 60_000;   // refresh the idle timer at most once a minute per session
const PREFIX = 'storefront:sess:';
const BY_SUBJECT = 'storefront:sub:';   // Keycloak session id -> our session ids, for back-channel logout

export interface User {
  sub: string;
  name: string;
  email?: string;
  roles: string[];
  /** Authentication methods of the sign-in (Keycloak `amr`), to require a second factor for staff pages. */
  amr: string[];
  /** When the user last authenticated, epoch seconds. */
  authTime: number;
}

export interface Tokens {
  access: string;
  refresh?: string;
  idToken?: string;
  /** Access token expiry, epoch ms. */
  expiresAt: number;
  /** Keycloak session id (`sid` claim), for back-channel logout. */
  sid?: string;
}

export interface SessionData {
  v: 1;
  createdAt: number;
  lastSeen: number;
  csrf: string;
  user?: User;
  tokens?: Tokens;
  /** Sign-in in progress (state, nonce, PKCE verifier) and where to return. */
  login?: { state: string; nonce: string; verifier: string; returnTo: string; startedAt: number };
  /** Checkout idempotency key, valid while the cart is unchanged (design/16 §16.8). */
  checkout?: { key: string; cartHash: string };
  /** A message for the next page ("Signed out", "Order placed"). */
  flash?: string;
}

export interface Session {
  id: string;
  data: SessionData;
  /** True when data changed and must be written back. */
  dirty: boolean;
  /** True when the id changed (sign-in) and the cookie must be replaced. */
  rotated: boolean;
  /** Set when the session was ended: the cookie is cleared. */
  destroyed: boolean;
}

export type RedisClient = ReturnType<typeof createClient>;

export class SessionStore {
  private readonly keys: Keyring;
  private readonly redis: RedisClient;
  private readonly now: () => number;

  constructor(redis: RedisClient, keySpec: string, now: () => number = Date.now) {
    this.redis = redis;
    this.now = now;
    this.keys = parseKeyring(keySpec);
  }

  create(): Session {
    const t = this.now();
    return { id: randomId(), data: { v: 1, createdAt: t, lastSeen: t, csrf: randomId() }, dirty: true, rotated: true, destroyed: false };
  }

  /** The session for a cookie value, or undefined if unknown, expired, tampered with or sealed with a retired key. */
  async load(id: string | undefined): Promise<Session | undefined> {
    if (!id || !/^[A-Za-z0-9_-]{43}$/.test(id)) return undefined;
    const sealed = await this.redis.get(storageKey(PREFIX, id));
    if (!sealed) return undefined;
    const json = open(this.keys, sealed, id);
    if (!json) return undefined;
    const data = JSON.parse(json) as SessionData;
    const t = this.now();
    if (data.v !== 1 || t - data.createdAt > ABSOLUTE_SECONDS * 1000) {
      await this.redis.del(storageKey(PREFIX, id));
      return undefined;
    }
    const session: Session = { id, data, dirty: false, rotated: false, destroyed: false };
    // Re-sealing with the current key also migrates sessions sealed with an older key.
    if (t - data.lastSeen > TOUCH_AFTER_MS || !sealed.startsWith(`${this.keys.current.id}.`)) {
      data.lastSeen = t;
      session.dirty = true;
    }
    return session;
  }

  async save(session: Session): Promise<void> {
    if (session.destroyed) return;
    const ttl = Math.max(1, Math.min(IDLE_SECONDS, Math.floor((session.data.createdAt + ABSOLUTE_SECONDS * 1000 - this.now()) / 1000)));
    await this.redis.set(storageKey(PREFIX, session.id), seal(this.keys, JSON.stringify(session.data), session.id), { expiration: { type: 'EX', value: ttl } });
    const sid = session.data.tokens?.sid;
    if (sid) {
      await this.redis.sAdd(storageKey(BY_SUBJECT, sid), session.id);
      await this.redis.expire(storageKey(BY_SUBJECT, sid), ABSOLUTE_SECONDS);
    }
    session.dirty = false;
  }

  /** New id, same data, new CSRF token: at sign-in and on role change, against session fixation. */
  async rotate(session: Session): Promise<void> {
    await this.redis.del(storageKey(PREFIX, session.id));
    session.id = randomId();
    session.data.csrf = randomId();
    session.data.createdAt = this.now();
    session.rotated = true;
    session.dirty = true;
  }

  async destroy(session: Session): Promise<void> {
    await this.redis.del(storageKey(PREFIX, session.id));
    session.destroyed = true;
    session.dirty = false;
  }

  /** Back-channel logout: ends every session of a Keycloak session. */
  async destroyByKeycloakSession(sid: string): Promise<number> {
    const ids = await this.redis.sMembers(storageKey(BY_SUBJECT, sid));
    if (ids.length > 0) await this.redis.del(ids.map((id) => storageKey(PREFIX, id)));
    await this.redis.del(storageKey(BY_SUBJECT, sid));
    return ids.length;
  }
}

/** `Set-Cookie` for the session: host-only, HTTPS only, invisible to scripts, not sent on cross-site POSTs. */
export function sessionCookie(session: Session): string {
  if (session.destroyed) return `${SESSION_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax`;
  return `${SESSION_COOKIE}=${session.id}; Path=/; Max-Age=${ABSOLUTE_SECONDS}; HttpOnly; Secure; SameSite=Lax`;
}
