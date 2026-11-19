import { Form, Link, redirect } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/home';
import { section } from '../../.server/pages.ts';
import { search, SearchParams } from '../../.server/search.ts';
import { shop } from '../../.server/shop.ts';
import { requireStaff } from '../../.server/staff.ts';
import { formatMoney } from '../../lib/money.ts';

export const meta: Route.MetaFunction = () => [{ title: 'Staff – Shop' }];

export async function loader({ request, context }: Route.LoaderArgs) {
  requireStaff(context, request);
  const id = new URL(request.url).searchParams.get('id');
  if (id && z.uuid().safeParse(id).success) throw redirect(`/staff/products/${id}`);
  return { newest: await section(search(shop(context).api, SearchParams.parse({ sort: 'newest' }), 20)) };
}

export default function StaffHome({ loaderData }: Route.ComponentProps) {
  return (
    <>
      <h1 className="text-2xl font-bold">Catalogue</h1>
      <Form method="get" className="mt-4 flex max-w-lg gap-2">
        <label htmlFor="id" className="sr-only">Product id</label>
        <input id="id" name="id" placeholder="Product id (UUID), including drafts" className="field" pattern="[0-9a-fA-F-]{36}" />
        <button className="btn-quiet" type="submit">Open</button>
      </Form>
      <h2 className="mt-8 text-lg font-semibold">Newest active products</h2>
      {loaderData.newest.ok ? (
        <table className="mt-2 w-full text-left text-sm">
          <thead className="text-muted"><tr><th className="py-1">SKU</th><th>Name</th><th>Price</th><th>Stock</th></tr></thead>
          <tbody className="divide-y divide-line">
            {loaderData.newest.value.items.map((p) => (
              <tr key={p.id}>
                <td className="py-2 font-mono">{p.sku}</td>
                <td><Link to={`/staff/products/${p.id}`} className="underline">{p.name}</Link></td>
                <td>{formatMoney(p.price)}</td>
                <td>{p.inStock ? 'In stock' : 'Out'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p className="mt-2 text-muted">The product list needs search, which is unavailable. Open a product by id instead.</p>}
    </>
  );
}
