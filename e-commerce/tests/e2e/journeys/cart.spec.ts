// Cart: a guest fills a cart, signs in, and the guest cart is merged into the account cart.
import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Api } from '../src/api.js';
import { addToCart, emptyCart, newProduct, type Cart } from '../src/shop.js';

test('a guest cart is merged into the account cart at sign-in', async () => {
  const [staff, bob] = await Promise.all([Api.as('sam'), Api.as('bob')]);
  const guestId = randomUUID();
  const guest = await Api.as(undefined, { 'x-guest-id': guestId });
  try {
    const shoes = await newProduct(staff, { price: '89.90', stock: 10 });
    const socks = await newProduct(staff, { price: '9.95', stock: 10 });
    await emptyCart(bob);

    const guestCart = await addToCart(guest, shoes.sku, 2);
    expect(guestCart.lines).toEqual([expect.objectContaining({ sku: shoes.sku, quantity: 2 })]);
    expect(guestCart.total).toEqual({ amount: '179.80', currency: 'EUR' });

    await addToCart(bob, socks.sku, 1);
    const merged = await Api.json<Cart>(await bob.post('/api/v1/cart/merge', { guestId }), 200);
    expect(merged.lines).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ sku: shoes.sku, quantity: 2 }),
        expect.objectContaining({ sku: socks.sku, quantity: 1 }),
      ]),
    );
    expect(merged.total).toEqual({ amount: '189.75', currency: 'EUR' });

    // quantity 0 removes a line
    const afterRemove = await addToCart(bob, socks.sku, 0);
    expect(afterRemove.lines.map((l) => l.sku)).toEqual([shoes.sku]);
  } finally {
    await emptyCart(bob);
    await Promise.all([staff, bob, guest].map((a) => a.dispose()));
  }
});

test('quantities outside 0..99 are rejected', async () => {
  const bob = await Api.as('bob');
  try {
    await Api.problem(await bob.put('/api/v1/cart/items/SKU-ANY', { quantity: 100 }), 400, 'VALIDATION_FAILED');
  } finally {
    await bob.dispose();
  }
});
