import { data } from 'react-router';
import type { Route } from './+types/category';
import { categoryTree, findCategory } from '../.server/catalog.ts';
import { PUBLIC_CACHE, section } from '../.server/pages.ts';
import { parseSearch, search } from '../.server/search.ts';
import { shop } from '../.server/shop.ts';
import { SearchResults } from '../components/SearchResults.tsx';

export const meta: Route.MetaFunction = ({ loaderData: data }) => [{ title: `${data?.name ?? 'Category'} – Shop` }];
export const headers: Route.HeadersFunction = () => PUBLIC_CACHE;

export async function loader({ request, params, context }: Route.LoaderArgs) {
  const { api } = shop(context);
  const category = findCategory(await categoryTree(api), params.slug);
  if (!category?.slug) throw data('Not found', { status: 404 });
  return { name: category.name ?? category.slug, results: await section(search(api, parseSearch(new URL(request.url), category.slug))) };
}

export default function Category({ loaderData }: Route.ComponentProps) {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">{loaderData.name}</h1>
      <SearchResults results={loaderData.results} title={loaderData.name} />
    </>
  );
}
