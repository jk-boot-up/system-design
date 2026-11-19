import { data, Form, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/stock';
import { runtime } from '../../.server/runtime.ts';
import { shop } from '../../.server/shop.ts';
import { requireStaff } from '../../.server/staff.ts';
import { Csrf, ProblemNotice } from '../../components/Notice.tsx';
import { ApiError, publicProblem } from '../../lib/problem.ts';
import { useRootData } from '../../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Stock – Staff' }];

export function loader({ request, context }: Route.LoaderArgs) {
  requireStaff(context, request);
  return null;
}

const SetStock = z.object({ sku: z.string().regex(/^[A-Z0-9][A-Z0-9-]{0,63}$/), available: z.coerce.number().int().min(0).max(100_000_000) });

/** Stock-take: sets the available quantity (inventory-service through the gateway's staff-only route). */
export async function action({ request, context }: Route.ActionArgs) {
  requireStaff(context, request);
  const parsed = SetStock.safeParse(Object.fromEntries(await request.formData()));
  if (!parsed.success) return data({ ok: false, problem: { status: 400, code: 'VALIDATION_FAILED' } }, { status: 400 });
  const s = shop(context);
  // inventory-service's contract is internal; the gateway exposes only this one staff route to it.
  const res = await fetch(`${runtime().config.GATEWAY_URL}/api/v1/admin/stock/${encodeURIComponent(parsed.data.sku)}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${s.session?.data.tokens?.access}`, 'Content-Type': 'application/json', 'X-Request-Id': s.requestId },
    body: JSON.stringify({ available: parsed.data.available }),
    signal: AbortSignal.timeout(8_000),
  }).catch(() => undefined);
  if (!res) return data({ ok: false, problem: { status: 503, code: 'DEPENDENCY_UNAVAILABLE' } }, { status: 503 });
  if (!res.ok) {
    const e = await ApiError.from(res);
    return data({ ok: false, problem: publicProblem(e) }, { status: e.status });
  }
  return { ok: true, problem: undefined, sku: parsed.data.sku, available: parsed.data.available };
}

export default function Stock({ actionData }: Route.ComponentProps) {
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  return (
    <>
      <h1 className="text-2xl font-bold">Stock</h1>
      <p className="mt-1 text-sm text-muted">Set the quantity counted on the shelf. Reserved items are kept on top of this.</p>
      <div aria-live="polite">
        {actionData?.ok && 'sku' in actionData && <p className="notice mt-4 border-ok text-ok">{actionData.sku} now has {actionData.available} available.</p>}
        {actionData?.problem && <ProblemNotice className="mt-4" problem={actionData.problem} />}
      </div>
      <Form method="post" className="mt-6 flex max-w-lg flex-wrap items-end gap-3">
        <Csrf token={root?.csrf} />
        <label className="block"><span className="font-semibold">SKU</span><input name="sku" required pattern="[A-Z0-9][A-Z0-9-]{0,63}" className="field mt-1 font-mono uppercase" /></label>
        <label className="block"><span className="font-semibold">Available</span><input name="available" type="number" min={0} required className="field mt-1 w-32" /></label>
        <button className="btn-primary" type="submit" disabled={busy}>Set stock</button>
      </Form>
    </>
  );
}
