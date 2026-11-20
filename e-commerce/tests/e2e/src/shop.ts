import { expect, request } from '@playwright/test';
import { Api, eventually, token, uniqueId, type Money } from './api.js';
import { env } from './env.js';

export type Product = { id: string; sku: string; name: string; brand: string; price: Money; status: string };
export type CartLine = { sku: string; quantity: number; available?: boolean };
export type Cart = { lines: CartLine[]; total: Money; pricing: 'CURRENT' | 'PENDING' };
export type Order = {
  id: string;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLING' | 'CANCELLED' | 'SHIPPED' | 'DELIVERED';
  cancelReason: string | null;
  lines: { sku: string; quantity: number; unitPrice: Money }[];
  total: Money;
};

/** Staff creates a category and an active product with stock. Every journey gets fresh SKUs, so runs never collide. */
export async function newProduct(
  staff: Api,
  opts: { name?: string; brand?: string; price?: string; stock?: number } = {},
): Promise<Product> {
  const sku = uniqueId('SKU-E2E');
  const category = await Api.json<{ id: string }>(
    await staff.post('/api/v1/admin/categories', { slug: uniqueId('e2e').toLowerCase(), name: 'End-to-end' }),
    201,
  );
  const product = await Api.json<Product>(
    await staff.post('/api/v1/admin/products', {
      sku,
      name: opts.name ?? `Trail Runner ${sku.slice(-6)}`,
      categoryId: category.id,
      brand: opts.brand ?? 'Acme',
      price: { amount: opts.price ?? '89.90', currency: 'EUR' },
      status: 'ACTIVE',
    }),
    201,
  );
  if (opts.stock !== undefined) await setStock(staff, sku, opts.stock);
  return product;
}

export async function setStock(staff: Api, sku: string, available: number): Promise<void> {
  const res = await staff.put(`/api/v1/admin/stock/${sku}`, { available });
  expect(res.ok(), `set stock ${sku}: ${res.status()} ${await res.text()}`).toBeTruthy();
}

/**
 * Stock as inventory-service sees it: its internal API, not routed by the gateway or the edge, and only for staff (or
 * order-service's own token), so the staff member's token is sent.
 */
export async function stockOf(sku: string): Promise<{ available: number; reserved?: number }> {
  const ctx = await request.newContext({ extraHTTPHeaders: { authorization: `Bearer ${await token('sam')}` } });
  const res = await ctx.get(`${env.inventory}/internal/stock/${sku}`);
  const body = await Api.json<{ available: number; reserved?: number }>(res, 200);
  await ctx.dispose();
  return body;
}

export async function emptyCart(api: Api): Promise<void> {
  const res = await api.delete('/api/v1/cart');
  expect([200, 204]).toContain(res.status());
}

export async function addToCart(api: Api, sku: string, quantity: number, headers?: Record<string, string>): Promise<Cart> {
  return Api.json<Cart>(await api.put(`/api/v1/cart/items/${sku}`, { quantity }, headers), 200);
}

/**
 * Places an order. The gateway admits three orders per user and then one every five seconds (429 + Retry-After); the
 * journeys share three test users, so a 429 is waited out and the same Idempotency-Key is sent again, exactly as a client
 * should. Returns the final response.
 */
export async function placeOrder(api: Api, paymentToken: string, idempotencyKey = uniqueId('e2e')) {
  for (let attempt = 0; ; attempt++) {
    const res = await api.post('/api/v1/orders', { paymentToken }, { 'idempotency-key': idempotencyKey });
    if (res.status() !== 429 || attempt >= 5) return { res, idempotencyKey };
    const wait = Number(res.headers()['retry-after'] ?? '5');
    await new Promise((r) => setTimeout(r, (wait + 0.5) * 1000));
  }
}

export async function orderEventually(api: Api, id: string, status: Order['status'], timeoutMs = 30_000): Promise<Order> {
  return eventually(`order ${id} ${status}`, async () => {
    const order = await Api.json<Order>(await api.get(`/api/v1/orders/${id}`), 200);
    return order.status === status ? order : undefined;
  }, timeoutMs);
}

export async function stockEventually(sku: string, available: number, timeoutMs = 30_000): Promise<void> {
  await eventually(`stock of ${sku} = ${available}`, async () => ((await stockOf(sku)).available === available ? true : undefined), timeoutMs);
}
