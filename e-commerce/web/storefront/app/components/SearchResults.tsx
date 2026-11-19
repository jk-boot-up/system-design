import { Form, Link, useSearchParams, useSubmit } from 'react-router';
import type { Problem } from '../lib/problem.ts';
import { ProblemNotice } from './Notice.tsx';
import { ProductCard, ProductGrid, type CardProduct } from './ProductCard.tsx';

export interface Results {
  total: number;
  nextCursor?: string;
  items: CardProduct[];
  brands: readonly { value?: string; count?: number }[];
}

/** Facets, sorting and results; plain GET forms, so it works without JavaScript and every state has a URL. */
export function SearchResults({ results, title }: { results: { ok: true; value: Results } | { ok: false; problem: Problem }; title: string }) {
  const [params] = useSearchParams();
  const submit = useSubmit();
  if (!results.ok) {
    return results.problem.code === 'SEARCH_UNAVAILABLE' || results.problem.status === 503
      ? <p role="status" className="notice border-line text-muted">Product listings are temporarily unavailable. Please try again shortly.</p>
      : <ProblemNotice problem={results.problem} />;
  }
  const { value } = results;
  const chosen = new Set(params.getAll('brand'));
  const next = new URLSearchParams(params);
  if (value.nextCursor) next.set('cursor', value.nextCursor);
  return (
    <div className="grid gap-6 md:grid-cols-[14rem_1fr]">
      <Form method="get" onChange={(e) => submit(e.currentTarget, { preventScrollReset: true })} aria-label="Filter results" className="space-y-4">
        {params.get('q') && <input type="hidden" name="q" value={params.get('q') ?? ''} />}
        <fieldset>
          <legend className="font-semibold">Brand</legend>
          {value.brands.length === 0 && <p className="text-sm text-muted">No brands</p>}
          {value.brands.map((b) => (
            <label key={b.value} className="flex items-center gap-2 py-0.5">
              <input type="checkbox" name="brand" value={b.value} defaultChecked={chosen.has(b.value ?? '')} />
              {b.value} <span className="text-sm text-muted">({b.count})</span>
            </label>
          ))}
        </fieldset>
        <label className="flex items-center gap-2">
          <input type="checkbox" name="inStock" value="true" defaultChecked={params.get('inStock') === 'true'} /> In stock only
        </label>
        <label className="block">
          <span className="font-semibold">Sort by</span>
          <select name="sort" defaultValue={params.get('sort') ?? 'relevance'} className="field mt-1">
            <option value="relevance">Relevance</option>
            <option value="price_asc">Price: low to high</option>
            <option value="price_desc">Price: high to low</option>
            <option value="newest">Newest</option>
          </select>
        </label>
        <noscript><button className="btn-quiet" type="submit">Apply</button></noscript>
      </Form>
      <section aria-labelledby="results-heading">
        <h2 id="results-heading" className="sr-only">{title}</h2>
        <p role="status" className="mb-3 text-sm text-muted">{value.total} {value.total === 1 ? 'product' : 'products'}</p>
        {value.items.length === 0 ? (
          <p>No products match. Try fewer filters.</p>
        ) : (
          <ProductGrid label={title}>{value.items.map((p) => <ProductCard key={p.id} p={p} />)}</ProductGrid>
        )}
        {value.nextCursor && <p className="mt-6"><Link className="btn-quiet" to={`?${next}`}>More results</Link></p>}
      </section>
    </div>
  );
}
