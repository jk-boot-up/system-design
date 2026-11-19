import { data, Form, redirect, useNavigation } from 'react-router';
import type { Route } from './+types/product-new';
import { categoryTree } from '../../.server/catalog.ts';
import { call } from '../../.server/gateway.ts';
import { categoryOptions, issues, ProductForm, Sku, toWrite } from '../../.server/product-form.ts';
import { shop } from '../../.server/shop.ts';
import { requireStaff } from '../../.server/staff.ts';
import { Csrf, ProblemNotice } from '../../components/Notice.tsx';
import { ProductFields } from '../../components/ProductFields.tsx';
import { ApiError, fieldErrors, publicProblem } from '../../lib/problem.ts';
import { useRootData } from '../../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'New product – Staff' }];

export async function loader({ request, context }: Route.LoaderArgs) {
  requireStaff(context, request);
  return { categories: categoryOptions(await categoryTree(shop(context).api)) };
}

export async function action({ request, context }: Route.ActionArgs) {
  requireStaff(context, request);
  const raw = Object.fromEntries(await request.formData());
  const sku = Sku.safeParse(raw.sku);
  const form = ProductForm.safeParse(raw);
  if (!sku.success || !form.success) {
    const errors = [...(sku.success ? [] : [{ field: 'sku', message: sku.error.issues[0]?.message ?? 'Invalid' }]), ...(form.success ? [] : issues(form.error))];
    return data({ problem: { status: 400, code: 'VALIDATION_FAILED', errors } }, { status: 400 });
  }
  try {
    const p = await call(shop(context).api.catalog.POST('/api/v1/admin/products', { body: { sku: sku.data, ...toWrite(form.data) } }));
    throw redirect(`/staff/products/${p.id}`);
  } catch (e) {
    if (e instanceof ApiError) return data({ problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
}

export default function NewProduct({ loaderData, actionData }: Route.ComponentProps) {
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  const errors = fieldErrors(actionData?.problem);
  const takenOrUnknown = actionData?.problem?.code === 'SKU_TAKEN' ? 'That SKU is already used.' : actionData?.problem?.code === 'UNKNOWN_CATEGORY' ? 'That category no longer exists.' : undefined;
  return (
    <>
      <h1 className="text-2xl font-bold">New product</h1>
      {takenOrUnknown ? <p role="alert" className="notice mt-4 border-danger text-danger">{takenOrUnknown}</p>
        : actionData?.problem && <ProblemNotice className="mt-4" problem={actionData.problem} />}
      <Form method="post" className="mt-6 max-w-xl space-y-4" noValidate>
        <Csrf token={root?.csrf} />
        <label className="block"><span className="font-semibold">SKU</span>
          <input name="sku" required pattern="[A-Z0-9-]{3,40}" className="field mt-1 font-mono uppercase" aria-invalid={Boolean(errors.sku)} aria-describedby={errors.sku ? 'sku-error' : 'sku-hint'} />
          <span id="sku-hint" className="text-sm text-muted">Cannot be changed later.</span>
          {errors.sku && <span id="sku-error" className="block text-sm text-danger">{errors.sku}</span>}
        </label>
        <ProductFields categories={loaderData.categories} errors={errors} />
        <button className="btn-primary" type="submit" disabled={busy}>Create product</button>
      </Form>
    </>
  );
}
