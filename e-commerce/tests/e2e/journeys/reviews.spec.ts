// @reviews: one review per customer per product; a verified badge when the customer bought it.
import { expect, test } from '@playwright/test';
import cassandra from 'cassandra-driver';
import { Api, customerId, eventually, requireProfile } from '../src/api.js';
import { env } from '../src/env.js';
import { addToCart, emptyCart, newProduct, placeOrder, type Order } from '../src/shop.js';

type Review = { rating: number; title: string; verified: boolean };
type Reviews = { summary: { count: number; average: number }; items: Review[] };

test('@reviews a buyer gets a verified review, a non-buyer an unverified one, and nobody reviews twice', async () => {
  await requireProfile('reviews', env.health.reviews);
  const [staff, ada, bob, anonymous] = await Promise.all([Api.as('sam'), Api.as('ada'), Api.as('bob'), Api.as()]);
  const db = new cassandra.Client({ contactPoints: [env.cassandra], localDataCenter: env.cassandraDc });
  try {
    const product = await newProduct(staff, { stock: 5 });
    await emptyCart(ada);
    await addToCart(ada, product.sku, 1);
    const order = await Api.json<Order>((await placeOrder(ada, 'tok_ok')).res, 201);
    expect(order.status).toBe('CONFIRMED');

    // review-service learns the purchase from OrderConfirmed; wait until it has.
    const ada_id = await customerId('ada');
    await eventually('purchase recorded by review-service', async () => {
      const rs = await db.execute('SELECT sku FROM reviews.purchases_by_customer WHERE customer_id = ? AND sku = ?',
        [ada_id, product.sku], { prepare: true });
      return rs.rowLength > 0 ? true : undefined;
    }, 60_000);

    const mine = await Api.json<Review>(await ada.post(`/api/v1/products/${product.sku}/reviews`, { rating: 5, title: 'Great', body: 'Fits well.' }), 201);
    expect(mine.verified).toBe(true);
    const bobs = await Api.json<Review>(await bob.post(`/api/v1/products/${product.sku}/reviews`, { rating: 3, title: 'Fine', body: 'Never bought it.' }), 201);
    expect(bobs.verified).toBe(false);

    await Api.problem(await ada.post(`/api/v1/products/${product.sku}/reviews`, { rating: 1, title: 'Again', body: 'x' }), 409, 'ALREADY_REVIEWED');
    await Api.problem(await anonymous.post(`/api/v1/products/${product.sku}/reviews`, { rating: 1, title: 'x', body: 'x' }), 401, 'UNAUTHENTICATED');

    const list = await Api.json<Reviews>(await anonymous.get(`/api/v1/products/${product.sku}/reviews`), 200);
    expect(list.summary).toEqual({ count: 2, average: 4 });
  } finally {
    await emptyCart(ada);
    await db.shutdown();
    await Promise.all([staff, ada, bob, anonymous].map((a) => a.dispose()));
  }
});
