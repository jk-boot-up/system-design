import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { Api, eventually } from '../src/api.js';
import { env } from '../src/env.js';
import { newProduct, stockOf } from '../src/shop.js';

// The storefront in a real browser, through the edge (design/16 §16.13): browse, guest cart, sign-in with Keycloak and
// merge, checkout outcomes, cancellation, staff pages, accessibility on every page, and the security properties of the
// backend-for-frontend (no tokens in the browser, cookie flags, CSP, CSRF).
test.use({ extraHTTPHeaders: {} });
test.describe.configure({ mode: 'serial' });

const PASSWORDS: Record<string, string> = { ada: 'ada', bob: 'bob', sam: 'sam' };

// Every page of every journey is watched for Content-Security-Policy violations (Trusted Types included): the page
// records them as they happen and each test fails if any page of ours reported one. Keycloak's pages are its own.
test.beforeEach(async ({ context }) => {
  await context.addInitScript(() => {
    const seen: string[] = [];
    (window as unknown as { __cspViolations: string[] }).__cspViolations = seen;
    document.addEventListener('securitypolicyviolation', (e) => seen.push(`${e.violatedDirective} ${e.blockedURI} ${e.sample}`));
  });
});
test.afterEach(async ({ context }) => {
  for (const p of context.pages()) {
    if (p.isClosed() || new URL(p.url()).origin !== new URL(env.edge).origin) continue;
    const violations = await p.evaluate(() => (window as unknown as { __cspViolations?: string[] }).__cspViolations ?? []);
    expect(violations, `CSP violations on ${p.url()}`).toEqual([]);
  }
});

async function signIn(page: Page, user: 'ada' | 'bob' | 'sam') {
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.waitForURL(/\/realms\/shop\//);
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(PASSWORDS[user]!);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === new URL(env.edge).origin);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

/** Every page passes axe's WCAG 2.2 A and AA rules. */
async function accessible(page: Page) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
  expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

/**
 * Pays, waiting out the checkout rate limit (journeys share the test users' budgets) and pressing again, as a shopper
 * would: the storefront re-sends the same server-held idempotency key, so a retry can never place a second order.
 */
async function pay(page: Page, card: string) {
  for (let attempt = 0; attempt < 8; attempt++) {
    await page.getByLabel(card).check();
    const answered = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname.startsWith('/checkout'));
    await page.getByRole('button', { name: /^Pay / }).click();
    const res = await answered;
    if (res.status() !== 429) return;
    await expect(page.getByText(/Too many requests/)).toBeVisible();
    await page.waitForTimeout(5_000);
  }
  throw new Error('still rate limited after 8 attempts');
}

test('a guest shops, signs in (cart merged), checks out, and cancels', async ({ page, context }) => {
  const staff = await Api.as('sam');
  const product = await newProduct(staff, { price: '24.50', stock: 10 });
  await (await Api.as('ada')).delete('/api/v1/cart');

  // Browse as a guest.
  await page.goto(`/p/${product.id}`);
  await expect(page.getByRole('heading', { level: 1, name: product.name })).toBeVisible();
  await expect(page.getByText('€24.50').first()).toBeVisible();
  await accessible(page);

  // Add to cart: the guest gets an HttpOnly cart cookie, nothing a script can read.
  await page.getByLabel('Quantity').fill('2');
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByText('Added to your cart')).toBeVisible();
  const guestCookie = (await context.cookies()).find((c) => c.name === '__Host-guest');
  expect(guestCookie).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Lax', path: '/' });
  expect(await page.evaluate(() => document.cookie)).toBe('');

  await page.getByRole('link', { name: /^Cart/ }).click();
  await expect(page.getByRole('heading', { name: 'Your cart' })).toBeVisible();
  await expect(page.getByText('€49.00').first()).toBeVisible();
  await accessible(page);

  // Sign in from the cart: Keycloak, back to the cart, guest cart merged into the account.
  await signIn(page, 'ada');
  await expect(page).toHaveURL(/\/cart$/);
  await expect(page.getByRole('list', { name: 'Items in your cart' }).getByText(product.name)).toBeVisible();
  const cookies = await context.cookies();
  expect(cookies.find((c) => c.name === '__Host-sid')).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Lax' });
  expect(cookies.find((c) => c.name === '__Host-guest')).toBeUndefined();

  // No token anywhere the page can reach.
  const storage = await page.evaluate(() => JSON.stringify({ l: { ...localStorage }, s: { ...sessionStorage }, c: document.cookie }));
  expect(storage).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}/);
  expect(await page.content()).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/);

  // Checkout.
  await page.getByRole('link', { name: 'Check out' }).click();
  await expect(page.getByRole('heading', { name: 'Checkout' })).toBeVisible();
  await accessible(page);
  await pay(page, 'Test card: approved');
  await expect(page.getByText('Thank you! Your order is confirmed.')).toBeVisible();
  await expect(page.getByText('Confirmed', { exact: true })).toBeVisible();
  await accessible(page);
  expect((await stockOf(product.sku)).available).toBe(8);

  // The back button and a resubmission cannot place a second order: the cart is empty now.
  await page.goto('/checkout');
  await expect(page).toHaveURL(/\/cart$/);

  // Cancel from the order page.
  await page.getByRole('link', { name: 'Orders' }).click();
  await page.getByRole('row').nth(1).getByRole('link').click();
  await page.getByRole('button', { name: 'Cancel order' }).click();
  await expect(page.getByText('Cancelled', { exact: true })).toBeVisible({ timeout: 20_000 });
  await eventually('stock returned', async () => ((await stockOf(product.sku)).available === 10 ? true : undefined));

  // Sign out ends the session here and at Keycloak.
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  expect((await context.cookies()).find((c) => c.name === '__Host-sid')).toBeUndefined();
});

test('a declined card keeps the cart, and the shopper can pay again', async ({ page }) => {
  const staff = await Api.as('sam');
  const product = await newProduct(staff, { price: '10.00', stock: 5 });
  await (await Api.as('ada')).delete('/api/v1/cart');
  await page.goto(`/p/${product.id}`);
  await signIn(page, 'ada');
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByText('Added to your cart')).toBeVisible();
  await page.goto('/checkout');
  await pay(page, 'Test card: declined');
  await expect(page.getByRole('alert')).toContainText('Your payment was declined');
  // Same cart, but the failed attempt's key was retired: the next press is a new attempt, not a replay of the decline.
  await pay(page, 'Test card: approved');
  await expect(page.getByText('Thank you! Your order is confirmed.')).toBeVisible();
});

test('pages carry a strict CSP with a fresh nonce and enforced Trusted Types, and cross-site or token-less posts are refused', async ({ page, request }) => {
  const first = await request.get('/');
  const second = await request.get('/');
  const csp = first.headers()['content-security-policy'] ?? '';
  expect(csp).toMatch(/script-src 'nonce-[A-Za-z0-9+/=]+' 'strict-dynamic'/);
  expect(csp).toContain("frame-ancestors 'none'");
  expect(csp).toContain("require-trusted-types-for 'script'");
  // The browser enforces it: a string assigned to a script sink throws instead of running.
  await page.goto('/');
  const blocked = await page.evaluate(() => {
    try {
      document.createElement('div').innerHTML = '<img src=x onerror=alert(1)>';
      return false;
    } catch (e) {
      return e instanceof TypeError;
    }
  });
  expect(blocked).toBe(true);
  await page.evaluate(() => { (window as unknown as { __cspViolations: string[] }).__cspViolations.length = 0; });  // ours, on purpose
  expect(csp).not.toEqual(second.headers()['content-security-policy']);
  expect(first.headers()['x-frame-options']).toBe('DENY');
  expect(first.headers()['strict-transport-security']).toContain('max-age=63072000');
  expect(first.headers()['cache-control']).toContain('no-cache');

  // A post from another site is refused, signed in or not (React Router's own Origin check answers 400, ours 403).
  expect([400, 403]).toContain((await request.post('/cart', { form: { intent: 'clear' }, headers: { Origin: 'https://evil.example' } })).status());
  expect([400, 403]).toContain((await request.post('/cart.data', { form: { intent: 'clear' }, headers: { Origin: 'https://evil.example' } })).status());
  // No Origin and no Sec-Fetch-Site: not provably same-origin, refused.
  expect((await request.post('/cart', { form: { intent: 'clear' } })).status()).toBe(403);

  // Signed in, a same-origin post without the session's form token is refused too.
  await page.goto('/');
  await signIn(page, 'bob');
  const status = await page.evaluate(async () =>
    (await fetch('/cart', { method: 'POST', body: new URLSearchParams({ intent: 'clear' }) })).status);
  expect(status).toBe(403);

  // An open redirect through returnTo is not possible.
  await page.goto('/login?returnTo=https://evil.example/');
  await expect(page).toHaveURL(new RegExp(`^${env.edge}/$`));
});

test('staff create a product with the staff pages and it is for sale', async ({ page }) => {
  const staff = await Api.as('sam');
  const sample = await newProduct(staff);   // also creates a category to choose
  const sku = `SKU-UI-${Date.now().toString(36).toUpperCase()}`;
  await page.goto('/');
  await signIn(page, 'sam');
  await page.getByRole('link', { name: 'Staff' }).click();
  await page.getByRole('link', { name: 'New product' }).click();
  await accessible(page);
  await page.getByLabel('SKU').fill(sku);
  await page.getByLabel('Name').fill('Staff Made Jacket');
  await page.getByLabel('Brand').fill('Northwind');
  await page.getByLabel('Category').selectOption({ index: 1 });
  await page.getByLabel('Price').fill('120');
  await page.getByLabel('Status').selectOption('ACTIVE');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByRole('heading', { name: 'Staff Made Jacket' })).toBeVisible();
  const productUrl = page.url().replace('/staff/products/', '/p/');

  // A stale edit is detected, not silently overwritten: someone else saves first, then this page's save is refused.
  const id = page.url().split('/').pop()!;
  const current = await staff.get(`/api/v1/admin/products/${id}`);
  const body = (await current.json()) as Record<string, unknown>;
  const { sku: _sku, id: _id, images: _images, version: _version, ...write } = body;
  await Api.json(await staff.put(`/api/v1/admin/products/${id}`, { ...write, name: 'Changed elsewhere' }, { 'If-Match': current.headers()['etag']! }), 200);
  await page.getByLabel('Name').fill('Staff Made Jacket II');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('alert')).toContainText('Someone else changed this product');
  await page.reload();
  await expect(page.getByLabel('Name')).toHaveValue('Changed elsewhere');
  await page.getByLabel('Name').fill('Staff Made Jacket II');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Saved.')).toBeVisible();

  // An image goes through the storefront to object storage and appears on the product.
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  await page.locator('input[name="image"]').setInputFiles({ name: 'jacket.png', mimeType: 'image/png', buffer: png });
  await page.getByRole('button', { name: 'Upload' }).click();
  await expect(page.getByText('Image added.')).toBeVisible();
  await expect(page.getByRole('img', { name: 'Image 0' }).or(page.getByRole('img', { name: 'Image 1' }))).toBeVisible();

  await page.getByRole('link', { name: 'Stock' }).click();
  await page.getByLabel('SKU').fill(sku);
  await page.getByLabel('Available').fill('7');
  await page.getByRole('button', { name: 'Set stock' }).click();
  await expect(page.getByText(`${sku} now has 7 available.`)).toBeVisible();

  await page.goto(productUrl);
  await expect(page.getByRole('heading', { level: 1, name: 'Staff Made Jacket II' })).toBeVisible();
  await expect(page.getByText('€120.00').first()).toBeVisible();
  expect(sample.sku).toBeTruthy();
});

test('shoppers cannot open staff pages', async ({ page }) => {
  await page.goto('/');
  await signIn(page, 'ada');
  const res = await page.goto('/staff');
  expect(res?.status()).toBe(403);
  await expect(page.getByRole('heading', { name: 'No access' })).toBeVisible();
});
