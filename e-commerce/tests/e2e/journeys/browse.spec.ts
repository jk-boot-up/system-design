// Browse: staff publishes a product; anyone can read it through the edge, which micro-caches public reads.
// Who may do what is enforced at the gateway and in the services, not by the client.
import { expect, test } from '@playwright/test';
import { Api } from '../src/api.js';
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
});
