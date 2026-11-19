// Checkout: the saga's outcomes as a shopper meets them. Each test starts from an empty cart and fresh SKUs.
import { expect, test } from '@playwright/test';
import { Api } from '../src/api.js';
import {
  addToCart, emptyCart, newProduct, orderEventually, placeOrder, stockEventually, stockOf,
  type Cart, type Order,
} from '../src/shop.js';

test.describe('checkout', () => {
  let staff: Api, ada: Api;
  test.beforeAll(async () => {
    [staff, ada] = await Promise.all([Api.as('sam'), Api.as('ada')]);
  });
  test.beforeEach(async () => emptyCart(ada));
  test.afterAll(async () => {
    await emptyCart(ada);
    await Promise.all([staff, ada].map((a) => a.dispose()));
  });

  test('happy path: confirmed, stock taken, cart emptied, and a replay returns the same order', async () => {
    const product = await newProduct(staff, { price: '89.90', stock: 5 });
    await addToCart(ada, product.sku, 2);

    const { res, idempotencyKey } = await placeOrder(ada, 'tok_ok');
    const order = await Api.json<Order>(res, 201);
    expect(order).toMatchObject({ status: 'CONFIRMED', total: { amount: '179.80', currency: 'EUR' } });
    expect(order.lines).toEqual([expect.objectContaining({ sku: product.sku, quantity: 2 })]);

    expect((await stockOf(product.sku)).available).toBe(3);

    // The same Idempotency-Key (a double click, a retry after a timeout) never places a second order.
    const replay = await placeOrder(ada, 'tok_ok', idempotencyKey);
    expect(replay.res.status()).toBeLessThan(300);
    expect(((await replay.res.json()) as Order).id).toBe(order.id);
    expect((await stockOf(product.sku)).available).toBe(3);

    // cart-service empties the cart when it hears OrderConfirmed.
    await expect.poll(async () => (await Api.json<Cart>(await ada.get('/api/v1/cart'), 200)).lines.length, { timeout: 30_000 }).toBe(0);

    const history = await Api.json<{ items: { id: string }[] }>(await ada.get('/api/v1/orders?limit=20'), 200);
    expect(history.items.map((o) => o.id)).toContain(order.id);
  });

  test('out of stock: 409, nothing reserved', async () => {
    const product = await newProduct(staff, { stock: 1 });
    await addToCart(ada, product.sku, 2);

    await Api.problem((await placeOrder(ada, 'tok_ok')).res, 409, 'OUT_OF_STOCK');
    await stockEventually(product.sku, 1);
  });

  test('declined card: 402, stock released, cart kept for another try', async () => {
    const product = await newProduct(staff, { stock: 4 });
    await addToCart(ada, product.sku, 1);

    await Api.problem((await placeOrder(ada, 'tok_decline')).res, 402, 'PAYMENT_DECLINED');
    await stockEventually(product.sku, 4);
    const cart = await Api.json<Cart>(await ada.get('/api/v1/cart'), 200);
    expect(cart.lines).toEqual([expect.objectContaining({ sku: product.sku, quantity: 1 })]);
  });

  test('payment outcome unknown: 504, the order is cancelled safely and stock released', async () => {
    const product = await newProduct(staff, { stock: 3 });
    await addToCart(ada, product.sku, 1);

    await Api.problem((await placeOrder(ada, 'tok_timeout')).res, 504, 'PAYMENT_UNKNOWN');
    await stockEventually(product.sku, 3);
  });

  test('an empty cart cannot be checked out', async () => {
    await Api.problem((await placeOrder(ada, 'tok_ok')).res, 422, 'CART_NOT_READY');
  });

  test('another customer cannot read the order', async () => {
    const bob = await Api.as('bob');
    try {
      const product = await newProduct(staff, { stock: 2 });
      await addToCart(ada, product.sku, 1);
      const order = await Api.json<Order>((await placeOrder(ada, 'tok_ok')).res, 201);
      expect([403, 404]).toContain((await bob.get(`/api/v1/orders/${order.id}`)).status());
      await orderEventually(ada, order.id, 'CONFIRMED');
    } finally {
      await bob.dispose();
    }
  });
});
