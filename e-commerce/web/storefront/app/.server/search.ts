import { z } from 'zod';
import { imageUrl } from '../lib/images.ts';
import { call, type Gateway } from './gateway.ts';
import { imageBase } from './pages.ts';

/** Search parameters from the page URL, validated before they reach the API (design/16 §16.6). */
export const SearchParams = z.object({
  q: z.string().trim().max(200).optional(),
  category: z.string().regex(/^[a-z0-9-]{1,64}$/).optional(),
  brand: z.array(z.string().max(100)).max(20).default([]),
  inStock: z.enum(['true']).optional(),
  sort: z.enum(['relevance', 'price_asc', 'price_desc', 'newest']).default('relevance'),
  cursor: z.string().max(500).optional(),
});
export type SearchParams = z.infer<typeof SearchParams>;

export function parseSearch(url: URL, category?: string): SearchParams {
  const p = url.searchParams;
  const parsed = SearchParams.safeParse({
    q: p.get('q') || undefined,
    category: category ?? (p.get('category') || undefined),
    brand: p.getAll('brand').filter(Boolean),
    inStock: p.get('inStock') || undefined,
    sort: p.get('sort') || undefined,
    cursor: p.get('cursor') || undefined,
  });
  return parsed.success ? parsed.data : SearchParams.parse({ category });
}

export async function search(api: Gateway, params: SearchParams, limit = 24) {
  const result = await call(api.search.GET('/api/v1/search', {
    params: {
      query: {
        q: params.q, category: params.category, brand: params.brand.length ? params.brand : undefined,
        inStock: params.inStock === 'true' ? true : undefined, sort: params.sort, cursor: params.cursor, limit,
      },
    },
  }));
  const base = imageBase();
  return {
    total: result.total ?? 0,
    nextCursor: result.nextCursor ?? undefined,
    items: (result.items ?? []).map((h) => ({ ...h, image: imageUrl(base, h.image) })),
    brands: result.facets?.brand ?? [],
  };
}
