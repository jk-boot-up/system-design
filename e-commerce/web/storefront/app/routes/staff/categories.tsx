import { data, Form, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/categories';
import { categoryTree } from '../../.server/catalog.ts';
import { call } from '../../.server/gateway.ts';
import { categoryOptions, issues } from '../../.server/product-form.ts';
import { shop } from '../../.server/shop.ts';
import { requireStaff } from '../../.server/staff.ts';
import { Csrf, ProblemNotice } from '../../components/Notice.tsx';
import { ApiError, fieldErrors, publicProblem } from '../../lib/problem.ts';
import { useRootData } from '../../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Categories – Staff' }];

export async function loader({ request, context }: Route.LoaderArgs) {
  requireStaff(context, request);
  return { categories: categoryOptions(await categoryTree(shop(context).api)) };
}

const NewCategory = z.object({
  slug: z.string().regex(/^[a-z0-9-]{1,64}$/, 'Lower-case letters, digits and dashes'),
  name: z.string().trim().min(1).max(100),
  parentId: z.union([z.uuid(), z.literal('')]).transform((v) => v || undefined),
});

export async function action({ request, context }: Route.ActionArgs) {
  requireStaff(context, request);
  const parsed = NewCategory.safeParse(Object.fromEntries(await request.formData()));
  if (!parsed.success) return data({ ok: false, problem: { status: 400, code: 'VALIDATION_FAILED', errors: issues(parsed.error) } }, { status: 400 });
  try {
    await call(shop(context).api.catalog.POST('/api/v1/admin/categories', { body: parsed.data }));
    return { ok: true, problem: undefined };
  } catch (e) {
    if (e instanceof ApiError) return data({ ok: false, problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
}

export default function Categories({ loaderData, actionData }: Route.ComponentProps) {
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  const errors = fieldErrors(actionData?.problem);
  return (
    <>
      <h1 className="text-2xl font-bold">Categories</h1>
      <p className="mt-1 text-sm text-muted">New categories appear in the shop within a minute.</p>
      <ul className="mt-4">{loaderData.categories.map((c) => <li key={c.id}>{c.label}</li>)}</ul>
      <h2 className="mt-8 text-lg font-semibold">Add a category</h2>
      <div aria-live="polite">
        {actionData?.ok && <p className="notice mt-2 border-ok text-ok">Category added.</p>}
        {actionData?.problem?.code === 'SLUG_TAKEN' ? <p role="alert" className="notice mt-2 border-danger text-danger">That slug is already used.</p>
          : actionData?.problem && <ProblemNotice className="mt-2" problem={actionData.problem} />}
      </div>
      <Form method="post" className="mt-3 max-w-md space-y-3" noValidate>
        <Csrf token={root?.csrf} />
        <label className="block"><span className="font-semibold">Name</span><input name="name" required className="field mt-1" />{errors.name && <span className="text-sm text-danger">{errors.name}</span>}</label>
        <label className="block"><span className="font-semibold">Slug (in the address)</span><input name="slug" required pattern="[a-z0-9-]{1,64}" className="field mt-1 font-mono" />{errors.slug && <span className="text-sm text-danger">{errors.slug}</span>}</label>
        <label className="block"><span className="font-semibold">Parent</span>
          <select name="parentId" className="field mt-1"><option value="">None (top level)</option>{loaderData.categories.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}</select></label>
        <button className="btn-primary" type="submit" disabled={busy}>Add category</button>
      </Form>
    </>
  );
}
