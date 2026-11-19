// Cancellation: a confirmed order is cancelled by its customer; payment is refunded and stock returns.
import { expect, test } from '@playwright/test';
import { Api } from '../src/api.js';
import { addToCart, emptyCart, newProduct, orderEventually, placeOrder, stockEventually, type Order } from '../src/shop.js';

test('a customer cancels a confirmed order and the stock comes back', async () => {
  const [staff, bob] = await Promise.all([Api.as('sam'), Api.as('bob')]);
  try {
    await emptyCart(bob);
    const product = await newProduct(staff, { stock: 6 });
    await addToCart(bob, product.sku, 2);
    const order = await Api.json<Order>((await placeOrder(bob, 'tok_ok')).res, 201);
    expect(order.status).toBe('CONFIRMED');
    await stockEventually(product.sku, 4);

    const cancelled = await Api.json<Order>(await bob.post(`/api/v1/orders/${order.id}/cancel`), 200);
    expect(['CANCELLING', 'CANCELLED']).toContain(cancelled.status);

    const final = await orderEventually(bob, order.id, 'CANCELLED');
    expect(final.cancelReason).toBeTruthy();
    await stockEventually(product.sku, 6);

    // Cancelling is idempotent: a retried request answers with the cancelled order and changes nothing.
    const again = await Api.json<Order>(await bob.post(`/api/v1/orders/${order.id}/cancel`), 200);
    expect(again).toMatchObject({ id: order.id, status: 'CANCELLED' });
    await stockEventually(product.sku, 6);
  } finally {
    await emptyCart(bob);
    await Promise.all([staff, bob].map((a) => a.dispose()));
  }
});
