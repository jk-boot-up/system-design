import type { components } from '../api/catalog';
import { call, type Gateway } from './gateway.ts';

export type Category = components['schemas']['Category'];

let cache: { at: number; value: Promise<readonly Category[]> } | undefined;
const TTL_MS = 60_000;

/** The category tree, shared by every page and kept for a minute: it is public and changes rarely. */
export function categoryTree(api: Gateway): Promise<readonly Category[]> {
  if (cache && Date.now() - cache.at < TTL_MS) return cache.value;
  const value = call(api.catalog.GET('/api/v1/categories')).then((c) => c ?? []);
  cache = { at: Date.now(), value };
  value.catch(() => { cache = undefined; });
  return value;
}

/** Depth-first search for a category by slug. */
export function findCategory(tree: readonly Category[], slug: string): Category | undefined {
  for (const c of tree) {
    if (c.slug === slug) return c;
    const hit = findCategory(c.children ?? [], slug);
    if (hit) return hit;
  }
  return undefined;
}
