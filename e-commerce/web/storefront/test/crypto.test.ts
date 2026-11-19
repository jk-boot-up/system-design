import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { open, parseKeyring, randomId, safeEqual, seal, storageKey } from '../app/.server/crypto.ts';

const key = () => randomBytes(32).toString('base64');

describe('session sealing', () => {
  const k1 = key();
  const k2 = key();

  it('opens what it sealed, for the same session id only', () => {
    const keys = parseKeyring(`a:${k1}`);
    const sealed = seal(keys, '{"x":1}', 'session-1');
    expect(open(keys, sealed, 'session-1')).toBe('{"x":1}');
    expect(open(keys, sealed, 'session-2')).toBeUndefined();
  });

  it('rejects tampering', () => {
    const keys = parseKeyring(`a:${k1}`);
    const sealed = seal(keys, 'secret', 'id');
    const [kid, iv, body] = sealed.split('.');
    const bytes = Buffer.from(body!, 'base64url');
    bytes[0] = bytes[0]! ^ 1;
    expect(open(keys, `${kid}.${iv}.${bytes.toString('base64url')}`, 'id')).toBeUndefined();
    expect(open(keys, 'garbage', 'id')).toBeUndefined();
  });

  it('rotates keys: new key seals, old key still opens, a retired key does not', () => {
    const old = parseKeyring(`old:${k1}`);
    const sealedWithOld = seal(old, 'v', 'id');
    const rotated = parseKeyring(`new:${k2},old:${k1}`);
    expect(open(rotated, sealedWithOld, 'id')).toBe('v');
    expect(seal(rotated, 'v', 'id').startsWith('new.')).toBe(true);
    expect(open(parseKeyring(`new:${k2}`), sealedWithOld, 'id')).toBeUndefined();
  });

  it('refuses malformed key specs', () => {
    expect(() => parseKeyring('')).toThrow();
    expect(() => parseKeyring(`a:${randomBytes(16).toString('base64')}`)).toThrow(/32 bytes/);
    expect(() => parseKeyring('no-colon')).toThrow();
  });

  it('makes 256-bit ids and stores only their hashes', () => {
    const id = randomId();
    expect(id).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(storageKey('p:', id)).not.toContain(id);
    expect(safeEqual('abc', 'abc')).toBe(true);
    expect(safeEqual('abc', 'abd')).toBe(false);
    expect(safeEqual('abc', 'ab')).toBe(false);
  });
});
