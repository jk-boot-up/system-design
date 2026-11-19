// @analytics: order events land in ClickHouse once each, without personal identifiers.
import { expect, request, test } from '@playwright/test';
import { Api, eventually, requireProfile } from '../src/api.js';
import { env } from '../src/env.js';
import { addToCart, emptyCart, newProduct, placeOrder, type Order } from '../src/shop.js';

test('@analytics a confirmed order appears in the order facts with a hashed customer', async () => {
  await requireProfile('analytics', env.health.analytics);
  const [staff, bob] = await Promise.all([Api.as('sam'), Api.as('bob')]);
  const ch = await request.newContext({
    baseURL: env.clickhouse,
    extraHTTPHeaders: { 'x-clickhouse-user': env.clickhouseUser, 'x-clickhouse-key': env.clickhousePassword },
  });
  const query = async (sql: string) => {
    const res = await ch.post('/', { data: `${sql} FORMAT JSONEachRow` });
    const text = await res.text();
    expect(res.status(), text).toBe(200);
    return text.trim().split('\n').filter(Boolean).map((l) => JSON.parse(l) as Record<string, unknown>);
  };
  try {
    const product = await newProduct(staff, { price: '19.99', stock: 5 });
    await emptyCart(bob);
    await addToCart(bob, product.sku, 2);
    const order = await Api.json<Order>((await placeOrder(bob, 'tok_ok')).res, 201);

    const rows = await eventually('order facts in ClickHouse', async () => {
      const r = await query(`SELECT event_type, total, currency, toString(customer_key) AS customer_key
        FROM analytics.order_facts FINAL WHERE order_id = toUUID('${order.id}') AND event_type = 'order.OrderConfirmed'`);
      return r.length > 0 ? r : undefined;
    }, 60_000);   // the Kafka engine writes in batches every few seconds; allow for a cold start
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ event_type: 'order.OrderConfirmed', total: 39.98, currency: 'EUR' });
    expect(String(rows[0]!.customer_key)).toMatch(/^\d+$/);   // a hash, not the customer id

    // The read-only dashboard user cannot change anything.
    const write = await ch.post('/', { data: 'TRUNCATE TABLE analytics.order_facts' });
    expect(write.status()).not.toBe(200);
  } finally {
    await emptyCart(bob);
    await ch.dispose();
    await Promise.all([staff, bob].map((a) => a.dispose()));
  }
});
