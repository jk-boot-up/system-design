import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { ABSOLUTE_SECONDS, IDLE_SECONDS, sessionCookie, SessionStore, type RedisClient } from '../app/.server/session.ts';

/** Enough of a Redis client for the session store, in memory. */
function fakeRedis() {
  const values = new Map<string, { v: string; ttl?: number }>();
  const sets = new Map<string, Set<string>>();
  const client = {
    values,
    async get(k: string) { return values.get(k)?.v ?? null; },
    async set(k: string, v: string, o?: { expiration?: { value: number } }) { values.set(k, { v, ttl: o?.expiration?.value }); return 'OK'; },
    async del(k: string | string[]) { for (const x of [k].flat()) { values.delete(x); sets.delete(x); } return 1; },
    async sAdd(k: string, m: string) { (sets.get(k) ?? sets.set(k, new Set()).get(k)!).add(m); return 1; },
    async sMembers(k: string) { return [...(sets.get(k) ?? [])]; },
    async expire() { return 1; },
  };
  return client;
}

const keys = `k1:${randomBytes(32).toString('base64')}`;

describe('session store', () => {
  it('stores sessions sealed, under a hash of the id, with the idle timeout', async () => {
    const redis = fakeRedis();
    const store = new SessionStore(redis as unknown as RedisClient, keys);
    const s = store.create();
    s.data.user = { sub: 'u1', name: 'Ada', roles: ['customer'], amr: ['pwd'], authTime: 0 };
    await store.save(s);
    const [storedKey, stored] = [...redis.values.entries()][0]!;
    expect(storedKey).not.toContain(s.id);
    expect(stored!.v).not.toContain('Ada');
    expect(stored!.ttl).toBe(IDLE_SECONDS);
    expect((await store.load(s.id))?.data.user?.name).toBe('Ada');
  });

  it('ignores unknown, malformed and expired sessions', async () => {
    let now = 1_000_000;
    const store = new SessionStore(fakeRedis() as unknown as RedisClient, keys, () => now);
    expect(await store.load(undefined)).toBeUndefined();
    expect(await store.load('../../etc')).toBeUndefined();
    const s = store.create();
    await store.save(s);
    now += ABSOLUTE_SECONDS * 1000 + 1;
    expect(await store.load(s.id)).toBeUndefined();
  });

  it('rotates the id and CSRF token at sign-in; the old id stops working', async () => {
    const store = new SessionStore(fakeRedis() as unknown as RedisClient, keys);
    const s = store.create();
    await store.save(s);
    const [oldId, oldCsrf] = [s.id, s.data.csrf];
    await store.rotate(s);
    await store.save(s);
    expect(s.id).not.toBe(oldId);
    expect(s.data.csrf).not.toBe(oldCsrf);
    expect(await store.load(oldId)).toBeUndefined();
    expect(await store.load(s.id)).toBeDefined();
  });

  it('ends every session of a Keycloak session (back-channel logout)', async () => {
    const store = new SessionStore(fakeRedis() as unknown as RedisClient, keys);
    const a = store.create();
    const b = store.create();
    for (const s of [a, b]) {
      s.data.tokens = { access: 'x', expiresAt: 0, sid: 'kc-1' };
      await store.save(s);
    }
    expect(await store.destroyByKeycloakSession('kc-1')).toBe(2);
    expect(await store.load(a.id)).toBeUndefined();
  });

  it('sets a host-only, secure, HttpOnly, SameSite=Lax cookie and clears it on sign-out', async () => {
    const store = new SessionStore(fakeRedis() as unknown as RedisClient, keys);
    const s = store.create();
    const cookie = sessionCookie(s);
    expect(cookie).toMatch(/^__Host-sid=[A-Za-z0-9_-]{43}; Path=\/; Max-Age=\d+; HttpOnly; Secure; SameSite=Lax$/);
    expect(cookie).not.toMatch(/Domain=/);
    await store.destroy(s);
    expect(sessionCookie(s)).toContain('Max-Age=0');
  });
});
