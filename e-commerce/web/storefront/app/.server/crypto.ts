import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from 'node:crypto';

/**
 * AES-256-GCM sealing of session data (design/16 §16.5). Sealed form: "<kid>.<iv>.<ciphertext+tag>" in base64url. The key
 * id lets several keys coexist: the first one seals, all of them open, so a key rotates without signing anyone out.
 * The session id is bound in as associated data, so a sealed value copied to another id does not open.
 */
export interface Keyring {
  current: { id: string; key: Buffer };
  all: Map<string, Buffer>;
}

export function parseKeyring(spec: string): Keyring {
  const entries = spec.split(',').map((s) => s.trim()).filter(Boolean).map((entry) => {
    const [id, b64] = entry.split(':');
    if (!id || !b64 || !/^[A-Za-z0-9_-]{1,32}$/.test(id)) throw new Error('SESSION_ENCRYPTION_KEYS: expected "kid:base64key,…"');
    const key = Buffer.from(b64, 'base64');
    if (key.length !== 32) throw new Error(`SESSION_ENCRYPTION_KEYS: key "${id}" must be 32 bytes`);
    return [id, key] as const;
  });
  const first = entries[0];
  if (!first) throw new Error('SESSION_ENCRYPTION_KEYS: no key');
  return { current: { id: first[0], key: first[1] }, all: new Map(entries) };
}

export function seal(keys: Keyring, plaintext: string, associated: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', keys.current.key, iv);
  cipher.setAAD(Buffer.from(associated));
  const body = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  return `${keys.current.id}.${iv.toString('base64url')}.${body.toString('base64url')}`;
}

/** Returns undefined for anything that does not open: unknown key, tampering, wrong id. */
export function open(keys: Keyring, sealed: string, associated: string): string | undefined {
  const [kid, ivB64, bodyB64] = sealed.split('.');
  const key = kid ? keys.all.get(kid) : undefined;
  if (!key || !ivB64 || !bodyB64) return undefined;
  try {
    const body = Buffer.from(bodyB64, 'base64url');
    const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(ivB64, 'base64url'));
    decipher.setAAD(Buffer.from(associated));
    decipher.setAuthTag(body.subarray(body.length - 16));
    return Buffer.concat([decipher.update(body.subarray(0, body.length - 16)), decipher.final()]).toString('utf8');
  } catch {
    return undefined;
  }
}

/** 256 random bits, URL-safe. */
export const randomId = (): string => randomBytes(32).toString('base64url');

/** Redis keys are hashes of the cookie value, so a Redis dump does not contain usable session ids. */
export const storageKey = (prefix: string, id: string): string => `${prefix}${createHash('sha256').update(id).digest('base64url')}`;

export function safeEqual(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}
