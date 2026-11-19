import { useEffect } from 'react';
import { data, Form, useNavigation, useRevalidator } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/order';
import { call } from '../.server/gateway.ts';
import { PRIVATE } from '../.server/pages.ts';
import { requireUser, shop } from '../.server/shop.ts';
import { Csrf, ProblemNotice } from '../components/Notice.tsx';
import { formatDate, OrderStatus } from '../components/OrderStatus.tsx';
import { formatMoney } from '../lib/money.ts';
import { ApiError, publicProblem } from '../lib/problem.ts';
import { useRootData } from '../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Order – Shop' }];
export const headers: Route.HeadersFunction = () => PRIVATE;

const Id = z.uuid();

export async function loader({ request, params, context }: Route.LoaderArgs) {
  requireUser(context, request);
  if (!Id.safeParse(params.id).success) throw data('Not found', { status: 404 });
  try {
    return { order: await call(shop(context).api.order.GET('/orders/{id}', { params: { path: { id: params.id } } })) };
  } catch (e) {
    // Another customer's order is a 404 too: its existence is not revealed.
    if (e instanceof ApiError && (e.status === 404 || e.status === 403)) throw data('Not found', { status: 404 });
    throw e;
  }
}

export async function action({ request, params, context }: Route.ActionArgs) {
  requireUser(context, request);
  try {
    await call(shop(context).api.order.POST('/orders/{id}/cancel', { params: { path: { id: params.id } } }));
    return { problem: undefined };
  } catch (e) {
    if (e instanceof ApiError) return data({ problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
}

const CANCELLABLE = new Set(['PENDING', 'CONFIRMED']);
const IN_FLIGHT = new Set(['PENDING', 'CANCELLING']);

export default function Order({ loaderData, actionData }: Route.ComponentProps) {
  const { order } = loaderData;
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  const revalidator = useRevalidator();
  // An order still in flight (payment being confirmed, cancellation running) is polled every 2 s until it settles.
  const inFlight = IN_FLIGHT.has(order.status ?? '');
  useEffect(() => {
    if (!inFlight) return;
    const t = setInterval(() => { if (revalidator.state === 'idle') void revalidator.revalidate(); }, 2_000);
    return () => clearInterval(t);
  }, [inFlight, revalidator]);
  return (
    <>
      <h1 className="text-2xl font-bold">Order</h1>
      <p className="mt-1 text-muted">Placed {formatDate(order.createdAt)} · <OrderStatus status={order.status} /></p>
      <p aria-live="polite" className="sr-only">Order status: {order.status}</p>
      {actionData?.problem && <ProblemNotice className="mt-4" problem={actionData.problem} />}
      <ul className="card mt-6 divide-y divide-line">
        {(order.lines ?? []).map((l) => (
          <li key={l.sku} className="flex justify-between p-3"><span>{l.quantity} × {l.productName}</span><span>{formatMoney(l.unitPrice)} each</span></li>
        ))}
      </ul>
      <p className="mt-3 text-right text-xl font-semibold">Total {formatMoney(order.total)}</p>
      {order.status === 'CANCELLED' && order.cancelReason && <p className="mt-3 text-muted">Cancelled: {order.cancelReason.replaceAll('_', ' ').toLowerCase()}. Any payment has been refunded.</p>}
      {CANCELLABLE.has(order.status ?? '') && (
        <Form method="post" className="mt-6">
          <Csrf token={root?.csrf} />
          <button className="btn-quiet" type="submit" disabled={busy}>Cancel order</button>
        </Form>
      )}
      <p className="mt-8 text-sm text-muted">Order number {order.id}</p>
    </>
  );
}
