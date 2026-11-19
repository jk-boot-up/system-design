import type { Route } from './+types/search';
import { PUBLIC_CACHE, section } from '../.server/pages.ts';
import { parseSearch, search } from '../.server/search.ts';
import { shop } from '../.server/shop.ts';
import { SearchResults } from '../components/SearchResults.tsx';

export const meta: Route.MetaFunction = ({ loaderData: data }) => [{ title: data?.q ? `“${data.q}” – Shop` : 'Search – Shop' }, { name: 'robots', content: 'noindex' }];
export const headers: Route.HeadersFunction = () => PUBLIC_CACHE;

export async function loader({ request, context }: Route.LoaderArgs) {
  const params = parseSearch(new URL(request.url));
  return { q: params.q, results: await section(search(shop(context).api, params)) };
}

export default function Search({ loaderData }: Route.ComponentProps) {
  const title = loaderData.q ? `Results for “${loaderData.q}”` : 'All products';
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">{title}</h1>
      <SearchResults results={loaderData.results} title={title} />
    </>
  );
}
