import { data, Form, Link, redirect, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/checkout';
import { checkoutKey, finishCheckout } from '../.server/checkout.ts';
import { call } from '../.server/gateway.ts';
import { PRIVATE } from '../.server/pages.ts';
import { requireUser, shop } from '../.server/shop.ts';
import { Csrf, ProblemNotice } from '../components/Notice.tsx';
import { formatMoney } from '../lib/money.ts';
import { ApiError, publicProblem } from '../lib/problem.ts';
import { useRootData } from '../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Checkout – Shop' }];
export const headers: Route.HeadersFunction = () => PRIVATE;

export async function loader({ request, context }: Route.LoaderArgs) {
  requireUser(context, request);
  const s = shop(context);
  const cart = await call(s.api.cart.GET('/api/v1/cart'));
  if (!cart.lines?.length) throw redirect('/cart');
  checkoutKey(s.session!, cart);   // created now, so a reload or double click reuses it
  return { cart };
}

/**
 * Demo payment: the simulated provider's test tokens. In production the provider's hosted fields (an iframe on its own
 * origin) produce the token, and card numbers never reach our servers (design/16 §16.8).
 */
const TEST_TOKENS = { tok_ok: 'Test card: approved', tok_decline: 'Test card: declined', tok_timeout: 'Test card: provider too slow' } as const;
const Place = z.object({ paymentToken: z.enum(Object.keys(TEST_TOKENS) as [keyof typeof TEST_TOKENS]) });

/** Outcomes after which the same key must not be reused: the order reached a final state. */
const FINAL = new Set([402, 409, 422, 504]);

export async function action({ request, context }: Route.ActionArgs) {
  requireUser(context, request);
  const s = shop(context);
  const form = Place.safeParse(Object.fromEntries(await request.formData()));
  if (!form.success) return data({ problem: { status: 400, code: 'VALIDATION_FAILED' }, paymentToken: undefined }, { status: 400 });
  const cart = await call(s.api.cart.GET('/api/v1/cart'));
  const key = checkoutKey(s.session!, cart);
  const { data: order, error, response } = await s.api.checkout.POST('/orders', {
    params: { header: { 'Idempotency-Key': key } },
    body: { paymentToken: form.data.paymentToken },
  }).catch(() => ({ data: undefined, error: undefined, response: new Response(null, { status: 503 }) }));
  if (response.ok && order?.id) {
    finishCheckout(s.session!);
    if (response.status !== 202) s.session!.data.flash = 'Thank you! Your order is confirmed.';
    throw redirect(`/orders/${order.id}`);
  }
  if (FINAL.has(response.status)) finishCheckout(s.session!);
  const problem = new ApiError({
    ...(typeof error === 'object' && error ? error : {}),
    status: response.status,
    code: (error as { code?: string } | undefined)?.code ?? 'DEPENDENCY_UNAVAILABLE',
    retryAfter: Number(response.headers.get('Retry-After')) || undefined,
  });
  return data({ problem: publicProblem(problem), paymentToken: form.data.paymentToken }, { status: response.status });
}

export default function Checkout({ loaderData, actionData }: Route.ComponentProps) {
  const { cart } = loaderData;
  const placing = useNavigation().state === 'submitting';
  return (
    <>
      <h1 className="text-2xl font-bold">Checkout</h1>
      {actionData?.problem && <ProblemNotice className="mt-4" problem={actionData.problem} />}
      <section aria-labelledby="summary" className="card mt-6 p-4">
        <h2 id="summary" className="font-semibold">Order summary</h2>
        <ul className="mt-2 divide-y divide-line">
          {(cart.lines ?? []).map((l) => (
            <li key={l.sku} className="flex justify-between py-2"><span>{l.quantity} × {l.name}</span><span>{formatMoney(l.lineTotal)}</span></li>
          ))}
        </ul>
        <p className="mt-2 flex justify-between border-t border-line pt-2 text-lg font-semibold"><span>Total</span><span>{formatMoney(cart.total)}</span></p>
      </section>
      {cart.pricing === 'PENDING' || !cart.checkoutReady ? (
        <p role="status" className="notice mt-6 border-warn text-warn">Prices are being confirmed. <Link to="/cart" className="underline">Back to cart</Link></p>
      ) : (
        <Form method="post" className="mt-6 space-y-4">
          <Csrf token={useCsrf()} />
          <fieldset>
            <legend className="font-semibold">Payment</legend>
            {Object.entries(TEST_TOKENS).map(([token, label]) => (
              <label key={token} className="flex items-center gap-2 py-1">
                <input type="radio" name="paymentToken" value={token} defaultChecked={(actionData?.paymentToken ?? 'tok_ok') === token} key={`${token}-${actionData?.paymentToken}`} /> {label}
              </label>
            ))}
          </fieldset>
          <button className="btn-primary" type="submit" disabled={placing}>{placing ? 'Placing your order…' : `Pay ${formatMoney(cart.total)}`}</button>
        </Form>
      )}
    </>
  );
}

function useCsrf(): string | undefined {
  return useRootData()?.csrf;
}
