import { data, Form, Link, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/cart';
import { call } from '../.server/gateway.ts';
import { imageBase, PRIVATE, section } from '../.server/pages.ts';
import { shop } from '../.server/shop.ts';
import { Csrf, ProblemNotice } from '../components/Notice.tsx';
import { imageUrl } from '../lib/images.ts';
import { formatMoney } from '../lib/money.ts';
import { ApiError, publicProblem } from '../lib/problem.ts';
import { useRootData } from '../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Cart – Shop' }];
export const headers: Route.HeadersFunction = () => PRIVATE;

export async function loader({ context }: Route.LoaderArgs) {
  const s = shop(context);
  if (!s.user && !s.guestId(false)) return { cart: { ok: true as const, value: { lines: [], checkoutReady: false } }, products: {} };
  const cart = await section(call(s.api.cart.GET('/api/v1/cart')));
  const skus = cart.ok ? (cart.value.lines ?? []).map((l) => l.sku!).filter(Boolean) : [];
  const found = skus.length ? await section(call(s.api.catalog.GET('/api/v1/products/by-sku', { params: { query: { sku: skus } } }))) : undefined;
  const base = imageBase();
  const products = Object.fromEntries((found?.ok ? found.value : []).map((p) => [p.sku, { id: p.id, image: imageUrl(base, p.images?.[0]?.url) }]));
  return { cart, products };
}

const Change = z.discriminatedUnion('intent', [
  z.object({ intent: z.literal('set'), sku: z.string().regex(/^[A-Z0-9-]{3,40}$/), quantity: z.coerce.number().int().min(0).max(99) }),
  z.object({ intent: z.literal('clear') }),
]);

export async function action({ request, context }: Route.ActionArgs) {
  const s = shop(context);
  const form = Change.safeParse(Object.fromEntries(await request.formData()));
  if (!form.success) return data({ problem: { status: 400, code: 'VALIDATION_FAILED' } }, { status: 400 });
  try {
    if (form.data.intent === 'clear') await call(s.api.cart.DELETE('/api/v1/cart'));
    else await call(s.api.cart.PUT('/api/v1/cart/items/{sku}', { params: { path: { sku: form.data.sku } }, body: { quantity: form.data.quantity } }));
    return { problem: undefined };
  } catch (e) {
    if (e instanceof ApiError) return data({ problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
}

export default function Cart({ loaderData, actionData }: Route.ComponentProps) {
  const { cart, products } = loaderData;
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  if (!cart.ok) return (<><h1 className="text-2xl font-bold">Your cart</h1><ProblemNotice className="mt-4" problem={cart.problem} /></>);
  const lines = cart.value.lines ?? [];
  return (
    <>
      <h1 className="text-2xl font-bold">Your cart</h1>
      {actionData?.problem && <ProblemNotice className="mt-4" problem={actionData.problem} />}
      {'pricing' in cart.value && cart.value.pricing === 'PENDING' && (
        <p role="status" className="notice mt-4 border-warn text-warn">Prices are being confirmed. You can check out once they are.</p>
      )}
      {lines.length === 0 ? (
        <p className="mt-6">Your cart is empty. <Link to="/" className="underline">Continue shopping</Link></p>
      ) : (
        <>
          <ul className="mt-6 divide-y divide-line card" aria-label="Items in your cart">
            {lines.map((l) => {
              const p = products[l.sku ?? ''];
              return (
                <li key={l.sku} className="flex flex-wrap items-center gap-4 p-4">
                  {p?.image ? <img src={p.image} alt="" width={64} height={64} className="h-16 w-16 rounded object-cover" /> : <div aria-hidden className="h-16 w-16 rounded bg-canvas" />}
                  <div className="min-w-48 flex-1">
                    {p?.id ? <Link to={`/p/${p.id}`} className="font-medium hover:underline">{l.name}</Link> : <span className="font-medium">{l.name}</span>}
                    <p className="text-sm text-muted">{formatMoney(l.unitPrice)} each</p>
                    {l.available === false && <p className="text-sm text-warn">No longer available</p>}
                  </div>
                  <Form method="post" className="flex items-end gap-2">
                    <Csrf token={root?.csrf} />
                    <input type="hidden" name="intent" value="set" />
                    <input type="hidden" name="sku" value={l.sku} />
                    <label className="text-sm">Quantity
                      <input name="quantity" type="number" min={0} max={99} defaultValue={l.quantity} className="field mt-1 w-20" aria-label={`Quantity of ${l.name}`} />
                    </label>
                    <button className="btn-quiet" type="submit" disabled={busy}>Update</button>
                  </Form>
                  <Form method="post">
                    <Csrf token={root?.csrf} />
                    <input type="hidden" name="intent" value="set" />
                    <input type="hidden" name="sku" value={l.sku} />
                    <input type="hidden" name="quantity" value="0" />
                    <button className="text-sm underline" type="submit" disabled={busy} aria-label={`Remove ${l.name}`}>Remove</button>
                  </Form>
                  <p className="w-24 text-right font-semibold">{formatMoney(l.lineTotal)}</p>
                </li>
              );
            })}
          </ul>
          <div className="mt-6 flex flex-wrap items-center justify-between gap-4">
            <Form method="post">
              <Csrf token={root?.csrf} />
              <input type="hidden" name="intent" value="clear" />
              <button className="btn-quiet" type="submit" disabled={busy}>Empty cart</button>
            </Form>
            <div className="text-right">
              <p className="text-xl font-semibold">Total {formatMoney('total' in cart.value ? cart.value.total : undefined)}</p>
              <Link to="/checkout" className={`btn-primary mt-2 ${cart.value.checkoutReady ? '' : 'pointer-events-none opacity-50'}`} aria-disabled={!cart.value.checkoutReady}>Check out</Link>
            </div>
          </div>
        </>
      )}
    </>
  );
}
