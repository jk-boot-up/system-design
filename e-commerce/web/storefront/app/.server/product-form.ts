import { z } from 'zod';
import type { Category } from './catalog.ts';

/** The staff product form, validated with the same limits as catalog-service's ProductWrite. */
export const ProductForm = z.object({
  name: z.string().trim().min(1).max(200),
  description: z.string().trim().max(10_000).optional(),
  categoryId: z.uuid(),
  brand: z.string().trim().min(1).max(100),
  amount: z.string().regex(/^\d{1,7}(\.\d{1,2})?$/, 'A price like 89.90'),
  currency: z.enum(['EUR']),
  status: z.enum(['DRAFT', 'ACTIVE', 'ARCHIVED']),
});
export type ProductForm = z.infer<typeof ProductForm>;

export const Sku = z.string().regex(/^[A-Z0-9-]{3,40}$/, '3–40 of A–Z, 0–9 and dashes');

export function toWrite(f: ProductForm) {
  return {
    name: f.name, description: f.description || undefined, categoryId: f.categoryId, brand: f.brand,
    price: { amount: f.amount.includes('.') ? f.amount : `${f.amount}.00`, currency: f.currency }, status: f.status,
  };
}

export function issues(error: z.ZodError) {
  return error.issues.map((i) => ({ field: String(i.path[0]), message: i.message }));
}

/** Flattened category options, children indented. */
export function categoryOptions(tree: readonly Category[], depth = 0): { id: string; label: string }[] {
  return tree.flatMap((c) => [{ id: c.id ?? '', label: `${'— '.repeat(depth)}${c.name}` }, ...categoryOptions(c.children ?? [], depth + 1)]);
}
