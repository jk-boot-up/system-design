// Browse: staff publishes a product; anyone can read it through the edge, which micro-caches public reads.
// Who may do what is enforced at the gateway and in the services, not by the client.
import { expect, request, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Api, token } from '../src/api.js';
import { env } from '../src/env.js';
import { newProduct, type Product } from '../src/shop.js';

test.describe('browse the catalogue', () => {
  let staff: Api, anonymous: Api, ada: Api;
  test.beforeAll(async () => {
    [staff, anonymous, ada] = await Promise.all([Api.as('sam'), Api.as(), Api.as('ada')]);
  });
  test.afterAll(async () => Promise.all([staff, anonymous, ada].map((a) => a.dispose())));

  test('a published product can be read by anyone, and the edge caches it', async () => {
    const product = await newProduct(staff, { price: '42.50' });

    const read = await Api.json<Product>(await anonymous.get(`/api/v1/products/${product.id}`), 200);
    expect(read).toMatchObject({ sku: product.sku, status: 'ACTIVE', price: { amount: '42.50', currency: 'EUR' } });

    await expect
      .poll(async () => (await anonymous.get(`/api/v1/products/${product.id}`)).headers()['x-cache'], { timeout: 5_000 })
      .toBe('HIT');
  });

  test('the category tree is public', async () => {
    const categories = await Api.json<unknown[]>(await anonymous.get('/api/v1/categories'), 200);
    expect(Array.isArray(categories)).toBeTruthy();
  });

  test('an unknown product is a 404 problem', async () => {
    await Api.problem(await anonymous.get('/api/v1/products/01930000-0000-7000-8000-000000000000'), 404, 'NOT_FOUND');
  });

  test('only staff may change the catalogue', async () => {
    const body = { slug: 'nope', name: 'Nope' };
    await Api.problem(await anonymous.post('/api/v1/admin/categories', body), 401, 'UNAUTHENTICATED');
    await Api.problem(await ada.post('/api/v1/admin/categories', body), 403, 'FORBIDDEN');
  });

  test('internal APIs are not reachable through the edge', async () => {
    expect((await anonymous.get('/internal/stock/ANY')).status()).toBe(404);
  });

  test('inside the network, internal APIs still need the calling service\'s own token', async () => {
    // Reaching the port is not enough (12-security-design §12.4): no token is 401, and a customer's token, whose
    // audience is the public API, is 403 even though the realm signed it.
    const direct = await request.newContext();
    const reserve = { data: { orderId: randomUUID(), lines: [{ sku: 'SKU-ANY', quantity: 1 }] } };
    await Api.problem(await direct.post(`${env.inventory}/internal/reservations`, reserve), 401, 'UNAUTHENTICATED');
    const res = await direct.post(`${env.inventory}/internal/reservations`, {
      ...reserve, headers: { authorization: `Bearer ${await token('ada')}` },
    });
    await Api.problem(res, 403, 'FORBIDDEN');
    await direct.dispose();
  });
});
