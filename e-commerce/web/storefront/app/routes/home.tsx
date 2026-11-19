import { Link } from 'react-router';
import type { Route } from './+types/home';
import { categoryTree } from '../.server/catalog.ts';
import { PUBLIC_CACHE, section } from '../.server/pages.ts';
import { search, SearchParams } from '../.server/search.ts';
import { shop } from '../.server/shop.ts';
import { ProductCard, ProductGrid } from '../components/ProductCard.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Shop' }, { name: 'description', content: 'Shoes, clothing and gear.' }];
export const headers: Route.HeadersFunction = () => PUBLIC_CACHE;

export async function loader({ context }: Route.LoaderArgs) {
  const { api } = shop(context);
  const [categories, newest] = await Promise.all([
    section(categoryTree(api)),
    section(search(api, SearchParams.parse({ sort: 'newest' }), 8)),
  ]);
  return { categories: categories.ok ? categories.value : [], newest };
}

export default function Home({ loaderData }: Route.ComponentProps) {
  const { categories, newest } = loaderData;
  return (
    <>
      <h1 className="text-3xl font-bold">Welcome to the shop</h1>
      {categories.length > 0 && (
        <section aria-labelledby="cats" className="mt-8">
          <h2 id="cats" className="text-xl font-semibold">Shop by category</h2>
          <ul className="mt-3 flex flex-wrap gap-3">
            {categories.map((c) => <li key={c.slug}><Link className="btn-quiet" to={`/c/${c.slug}`}>{c.name}</Link></li>)}
          </ul>
        </section>
      )}
      <section aria-labelledby="new" className="mt-10">
        <h2 id="new" className="mb-3 text-xl font-semibold">New in</h2>
        {newest.ok ? (
          <ProductGrid label="New in">{newest.value.items.map((p) => <ProductCard key={p.id} p={p} />)}</ProductGrid>
        ) : (
          <p role="status" className="notice border-line text-muted">New products are temporarily unavailable.</p>
        )}
      </section>
    </>
  );
}
