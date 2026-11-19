// @search: catalogue and stock events reach the OpenSearch read model; facets and the in-stock filter follow them.
import { expect, test } from '@playwright/test';
import { Api, eventually, requireProfile, uniqueId } from '../src/api.js';
import { env } from '../src/env.js';
import { newProduct, setStock } from '../src/shop.js';

type Hit = { sku: string; name: string; brand: string; inStock?: boolean };
type Results = { items: Hit[]; facets: { brand: { value: string; count: number }[] }; total: number; nextCursor: string | null };

test('@search a new product becomes searchable, with brand facets, and leaves in-stock results when sold out', async () => {
  await requireProfile('search', env.health.search);
  const [staff, anonymous] = await Promise.all([Api.as('sam'), Api.as()]);
  try {
    const word = uniqueId('zephyr').replace(/[^A-Z0-9]/g, '').toLowerCase();
    const brand = `Brand${word.slice(-5)}`;
    const product = await newProduct(staff, { name: `Trail ${word} runner`, brand, stock: 3 });
    const q = `/api/v1/search?q=${word}`;

    const found = await eventually('product in search results', async () => {
      const r = await Api.json<Results>(await anonymous.get(q), 200);
      return r.items.some((h) => h.sku === product.sku) ? r : undefined;
    }, 60_000);
    expect(found.facets.brand).toEqual(expect.arrayContaining([expect.objectContaining({ value: brand, count: 1 })]));

    await Api.json<Results>(await anonymous.get(`${q}&brand=${brand}&inStock=true`), 200);
    await setStock(staff, product.sku, 0);
    await eventually('sold-out product leaves in-stock results', async () => {
      const r = await Api.json<Results>(await anonymous.get(`${q}&inStock=true&limit=20&brand=${brand}`), 200);
      return r.items.some((h) => h.sku === product.sku) ? undefined : true;
    }, 60_000);
  } finally {
    await Promise.all([staff, anonymous].map((a) => a.dispose()));
  }
});
