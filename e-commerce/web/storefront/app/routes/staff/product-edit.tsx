import { data, Form, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/product-edit';
import { categoryTree } from '../../.server/catalog.ts';
import { call } from '../../.server/gateway.ts';
import { imageBase } from '../../.server/pages.ts';
import { categoryOptions, issues, ProductForm, toWrite } from '../../.server/product-form.ts';
import { shop } from '../../.server/shop.ts';
import { putPresigned } from '../../.server/upload.ts';
import { requireStaff } from '../../.server/staff.ts';
import { Csrf, ProblemNotice } from '../../components/Notice.tsx';
import { ProductFields } from '../../components/ProductFields.tsx';
import { imageUrl } from '../../lib/images.ts';
import { ApiError, fieldErrors, publicProblem } from '../../lib/problem.ts';
import { useRootData } from '../../root.tsx';

export const meta: Route.MetaFunction = ({ loaderData: data }) => [{ title: `${data?.product.name ?? 'Product'} – Staff` }];

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

export async function loader({ request, params, context }: Route.LoaderArgs) {
  requireStaff(context, request);
  if (!z.uuid().safeParse(params.id).success) throw data('Not found', { status: 404 });
  const { api } = shop(context);
  const res = await api.catalog.GET('/api/v1/admin/products/{id}', { params: { path: { id: params.id } } });
  if (res.response.status === 404) throw data('Not found', { status: 404 });
  const product = await call(Promise.resolve(res));
  const base = imageBase();
  return {
    product: { ...product, images: (product.images ?? []).map((i) => ({ ...i, url: imageUrl(base, i.url) })) },
    // The ETag the update must send back in If-Match: a concurrent edit by someone else is detected, not overwritten.
    etag: res.response.headers.get('ETag') ?? '',
    categories: categoryOptions(await categoryTree(api)),
  };
}

export async function action({ request, params, context }: Route.ActionArgs) {
  requireStaff(context, request);
  const { api } = shop(context);
  const form = await request.formData();
  try {
    if (form.get('intent') === 'image') return await uploadImage(api, params.id, form.get('image'));
    const parsed = ProductForm.safeParse(Object.fromEntries(form));
    if (!parsed.success) return data({ ok: false as const, problem: { status: 400, code: 'VALIDATION_FAILED', errors: issues(parsed.error) } }, { status: 400 });
    const etag = String(form.get('etag') ?? '');
    await call(api.catalog.PUT('/api/v1/admin/products/{id}', {
      params: { path: { id: params.id }, header: { 'If-Match': etag } as never },
      headers: { 'If-Match': etag },
      body: toWrite(parsed.data),
    }));
    return { ok: true as const, message: 'Saved.' };
  } catch (e) {
    if (e instanceof ApiError) return data({ ok: false as const, problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
}

/**
 * The image goes through the storefront to object storage (presigned URL from catalog-service), so the browser only
 * ever talks to our own origin and the CSP needs no storage host.
 */
async function uploadImage(api: ReturnType<typeof shop>['api'], id: string, file: FormDataEntryValue | null) {
  if (!(file instanceof File) || file.size === 0) return data({ ok: false as const, problem: { status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'image', message: 'Choose an image' }] } }, { status: 400 });
  const type = IMAGE_TYPES.find((t) => t === file.type);
  if (!type || file.size > MAX_IMAGE_BYTES) {
    return data({ ok: false as const, problem: { status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'image', message: 'JPEG, PNG or WebP up to 5 MB' }] } }, { status: 400 });
  }
  const ticket = await call(api.catalog.POST('/api/v1/admin/products/{id}/images', { params: { path: { id } }, body: { contentType: type, sizeBytes: file.size } }));
  const stored = await putPresigned(ticket.uploadUrl!, type, new Uint8Array(await file.arrayBuffer())).catch(() => false);
  if (!stored) throw new ApiError({ status: 503, code: 'DEPENDENCY_UNAVAILABLE' });
  await call(api.catalog.POST('/api/v1/admin/products/{id}/images/confirm', { params: { path: { id } }, body: { objectKey: ticket.objectKey! } }));
  return { ok: true as const, message: 'Image added.' };
}

export default function EditProduct({ loaderData, actionData }: Route.ComponentProps) {
  const { product, etag, categories } = loaderData;
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  const problem = actionData && !actionData.ok ? actionData.problem : undefined;
  const errors = fieldErrors(problem);
  return (
    <>
      <h1 className="text-2xl font-bold">{product.name}</h1>
      <p className="font-mono text-sm text-muted">{product.sku} · version {product.version}</p>
      <div aria-live="polite" className="mt-4">
        {actionData?.ok && <p className="notice border-ok text-ok">{actionData.message}</p>}
        {problem?.code === 'VERSION_MISMATCH'
          ? <p role="alert" className="notice border-danger text-danger">Someone else changed this product while you were editing. <a href="" className="underline">Reload</a> to see their changes, then make yours again.</p>
          : problem && <ProblemNotice problem={problem} />}
      </div>
      <Form method="post" className="mt-6 max-w-xl space-y-4" noValidate>
        <Csrf token={root?.csrf} />
        <input type="hidden" name="etag" value={etag} />
        <ProductFields values={product} categories={categories} errors={errors} />
        <button className="btn-primary" type="submit" name="intent" value="save" disabled={busy}>Save</button>
      </Form>
      <section aria-labelledby="images" className="mt-10 max-w-xl">
        <h2 id="images" className="text-lg font-semibold">Images</h2>
        <ul className="mt-2 flex flex-wrap gap-3">
          {product.images.map((i) => <li key={i.position}><img src={i.url} alt={`Image ${i.position}`} width={96} height={96} className="h-24 w-24 rounded object-cover" /></li>)}
        </ul>
        <Form method="post" encType="multipart/form-data" className="mt-3 flex items-end gap-2">
          <Csrf token={root?.csrf} />
          <label className="block"><span className="text-sm">JPEG, PNG or WebP, up to 5 MB</span>
            <input type="file" name="image" accept={IMAGE_TYPES.join(',')} required className="mt-1 block" aria-invalid={Boolean(errors.image)} /></label>
          <button className="btn-quiet" type="submit" name="intent" value="image" disabled={busy}>Upload</button>
        </Form>
        {errors.image && <p className="text-sm text-danger">{errors.image}</p>}
      </section>
    </>
  );
}
