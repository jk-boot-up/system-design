// @async: what happens after an order without the shopper waiting: the confirmation email (Kafka → RabbitMQ → SMTP,
// exactly once) and co-purchase recommendations.
import { expect, request, test } from '@playwright/test';
import { Api, eventually, requireProfile } from '../src/api.js';
import { env } from '../src/env.js';
import { addToCart, emptyCart, newProduct, placeOrder, type Order } from '../src/shop.js';

type MailList = { messages: { ID: string; Subject: string; To: { Address: string }[] }[]; messages_count: number };

test('@async an order sends one confirmation email and teaches recommendations what is bought together', async () => {
  await requireProfile('async', env.health.async);
  await requireProfile('async', env.health.recommendations);
  const [staff, ada, anonymous] = await Promise.all([Api.as('sam'), Api.as('ada'), Api.as()]);
  const mail = await request.newContext({ baseURL: env.mailpit });
  try {
    const shoes = await newProduct(staff, { stock: 5 });
    const socks = await newProduct(staff, { price: '9.95', stock: 5 });
    await emptyCart(ada);
    await addToCart(ada, shoes.sku, 1);
    await addToCart(ada, socks.sku, 2);
    const order = await Api.json<Order>((await placeOrder(ada, 'tok_ok')).res, 201);
    expect(order.status).toBe('CONFIRMED');

    const found = await eventually('confirmation email in Mailpit', async () => {
      const list = await Api.json<MailList>(await mail.get(`/api/v1/search?query=${encodeURIComponent(`"${order.id}"`)}`), 200);
      return list.messages_count > 0 ? list : undefined;
    }, 60_000);
    expect(found.messages[0]).toMatchObject({ Subject: 'Your order is confirmed', To: [expect.objectContaining({ Address: 'ada@example.com' })] });

    const body = await (await mail.get(`/api/v1/message/${found.messages[0]!.ID}`)).json() as { Text: string };
    expect(body.Text).toContain(shoes.sku);
    expect(body.Text).toContain('Total paid');

    // Exactly once: still a single email a little later (no duplicate from redelivery).
    await new Promise((r) => setTimeout(r, 3_000));
    const again = await Api.json<MailList>(await mail.get(`/api/v1/search?query=${encodeURIComponent(`"${order.id}"`)}`), 200);
    expect(again.messages_count).toBe(1);

    const recs = await eventually('socks recommended with the shoes', async () => {
      const r = await Api.json<{ items: { sku: string; score: number }[] }>(await anonymous.get(`/api/v1/products/${shoes.sku}/recommendations?limit=10`), 200);
      return r.items.some((i) => i.sku === socks.sku) ? r : undefined;
    }, 60_000);
    expect(recs.items.find((i) => i.sku === socks.sku)!.score).toBeGreaterThanOrEqual(1);
  } finally {
    await emptyCart(ada);
    await mail.dispose();
    await Promise.all([staff, ada, anonymous].map((a) => a.dispose()));
  }
});
