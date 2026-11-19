import { Link } from 'react-router';
import type { Route } from './+types/orders';
import { call } from '../.server/gateway.ts';
import { PRIVATE } from '../.server/pages.ts';
import { requireUser, shop } from '../.server/shop.ts';
import { formatDate, OrderStatus } from '../components/OrderStatus.tsx';
import { formatMoney } from '../lib/money.ts';

export const meta: Route.MetaFunction = () => [{ title: 'Your orders – Shop' }];
export const headers: Route.HeadersFunction = () => PRIVATE;

export async function loader({ request, context }: Route.LoaderArgs) {
  requireUser(context, request);
  const cursor = new URL(request.url).searchParams.get('cursor') ?? undefined;
  const page = await call(shop(context).api.order.GET('/orders', { params: { query: { limit: 20, cursor } } }));
  return { orders: page.items ?? [], nextCursor: page.nextCursor ?? undefined };
}

export default function Orders({ loaderData }: Route.ComponentProps) {
  const { orders, nextCursor } = loaderData;
  return (
    <>
      <h1 className="text-2xl font-bold">Your orders</h1>
      {orders.length === 0 ? <p className="mt-6">No orders yet. <Link to="/" className="underline">Start shopping</Link></p> : (
        <table className="mt-6 w-full text-left">
          <caption className="sr-only">Your orders, newest first</caption>
          <thead className="text-sm text-muted"><tr><th className="py-2">Placed</th><th>Items</th><th>Total</th><th>Status</th></tr></thead>
          <tbody className="divide-y divide-line">
            {orders.map((o) => (
              <tr key={o.id}>
                <td className="py-3"><Link to={`/orders/${o.id}`} className="underline">{formatDate(o.createdAt)}</Link></td>
                <td>{(o.lines ?? []).reduce((n, l) => n + (l.quantity ?? 0), 0)}</td>
                <td>{formatMoney(o.total)}</td>
                <td><OrderStatus status={o.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {nextCursor && <p className="mt-6"><Link className="btn-quiet" to={`?cursor=${encodeURIComponent(nextCursor)}`}>Older orders</Link></p>}
    </>
  );
}
