import { describe, expect, it } from 'vitest';
import { checkoutKey, finishCheckout } from '../app/.server/checkout.ts';
import type { Session } from '../app/.server/session.ts';
import { parseCookies, safeReturnTo } from '../app/.server/shop.ts';

describe('returnTo', () => {
  it.each([
    ['/cart', '/cart'],
    ['/p/1?x=2', '/p/1?x=2'],
    ['https://evil.example/', '/'],
    ['//evil.example/', '/'],
    ['/\\evil.example', '/'],
    ['javascript:alert(1)', '/'],
    ['/ok\r\nSet-Cookie: x', '/'],
    [null, '/'],
  ])('%s → %s', (input, expected) => expect(safeReturnTo(input)).toBe(expected));
});

describe('cookies', () => {
  it('parses the first value of each name', () => {
    expect(parseCookies('a=1; __Host-sid=abc; a=2')).toEqual({ a: '1', '__Host-sid': 'abc' });
    expect(parseCookies(null)).toEqual({});
  });
});

describe('checkout idempotency key', () => {
  const session = (): Session => ({ id: 'x', data: { v: 1, createdAt: 0, lastSeen: 0, csrf: 'c' }, dirty: false, rotated: false, destroyed: false });
  const cart = (qty: number) => ({ lines: [{ sku: 'A', quantity: qty, unitPrice: { amount: '1.00', currency: 'EUR' } }], total: { amount: `${qty}.00`, currency: 'EUR' } });

  it('is reused while the cart is unchanged (double click, reload, retry)', () => {
    const s = session();
    const k = checkoutKey(s, cart(1));
    expect(checkoutKey(s, cart(1))).toBe(k);
    expect(k).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
  });

  it('is new when the cart changes or after a finished order', () => {
    const s = session();
    const k = checkoutKey(s, cart(1));
    expect(checkoutKey(s, cart(2))).not.toBe(k);
    const k2 = checkoutKey(s, cart(2));
    finishCheckout(s);
    expect(checkoutKey(s, cart(2))).not.toBe(k2);
  });
});
