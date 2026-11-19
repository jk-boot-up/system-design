import { Suspense } from 'react';
import { Await, data, Form, Link, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/product';
import { call } from '../.server/gateway.ts';
import { imageBase, PUBLIC_CACHE, section } from '../.server/pages.ts';
import { shop } from '../.server/shop.ts';
import { Csrf, ProblemNotice } from '../components/Notice.tsx';
import { ProductCard, ProductGrid } from '../components/ProductCard.tsx';
import { imageUrl } from '../lib/images.ts';
import { formatMoney } from '../lib/money.ts';
import { ApiError, publicProblem } from '../lib/problem.ts';
import { useRootData } from '../root.tsx';

const Id = z.uuid();

export const meta: Route.MetaFunction = ({ loaderData: data }) =>
  data ? [{ title: `${data.product.name} – Shop` }, { name: 'description', content: data.product.description?.slice(0, 160) ?? '' }] : [];
export const headers: Route.HeadersFunction = () => PUBLIC_CACHE;

export async function loader({ params, context }: Route.LoaderArgs) {
  if (!Id.safeParse(params.id).success) throw data('Not found', { status: 404 });
  const { api } = shop(context);
  let product;
  try {
    product = await call(api.catalog.GET('/api/v1/products/{id}', { params: { path: { id: params.id } } }));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) throw data('Not found', { status: 404 });
    throw e;
  }
  const sku = product.sku ?? '';
  const base = imageBase();
  // Reviews and recommendations stream in after the page and fail on their own (design/16 §16.7).
  const reviews = section(call(api.review.GET('/api/v1/products/{sku}/reviews', { params: { path: { sku }, query: { limit: 10 } } })));
  const recommended = section(api.recommendations(sku, 8).then(async (items) => {
    if (items.length === 0) return [];
    const products = await call(api.catalog.GET('/api/v1/products/by-sku', { params: { query: { sku: items.map((i) => i.sku) } } }));
    return products.map((p) => ({ id: p.id, sku: p.sku, name: p.name, brand: p.brand, price: p.price, image: imageUrl(base, p.images?.[0]?.url) }));
  }));
  return {
    product: { ...product, images: (product.images ?? []).map((i) => ({ ...i, url: imageUrl(base, i.url) })) },
    reviews,
    recommended,
  };
}

const AddToCart = z.object({ quantity: z.coerce.number().int().min(1).max(99) });

export async function action({ request, params, context }: Route.ActionArgs) {
  const s = shop(context);
  const form = AddToCart.safeParse(Object.fromEntries(await request.formData()));
  if (!form.success) return data({ ok: false as const, problem: { status: 400, code: 'VALIDATION_FAILED' } }, { status: 400 });
  if (!s.user) s.guestId(true);   // a guest gets a cart cookie on the first add
  try {
    const product = await call(s.api.catalog.GET('/api/v1/products/{id}', { params: { path: { id: params.id } } }));
    const sku = product.sku!;
    const cart = await call(s.api.cart.GET('/api/v1/cart'));
    const current = cart.lines?.find((l) => l.sku === sku)?.quantity ?? 0;
    await call(s.api.cart.PUT('/api/v1/cart/items/{sku}', {
      params: { path: { sku } },
      body: { quantity: Math.min(99, current + form.data.quantity) },
    }));
    return { ok: true as const };
  } catch (e) {
    if (e instanceof ApiError) return data({ ok: false as const, problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
}

export default function Product({ loaderData, actionData }: Route.ComponentProps) {
  const { product, reviews, recommended } = loaderData;
  const root = useRootData();
  const adding = useNavigation().formMethod === 'POST';
  const image = product.images[0];
  return (
    <article>
      <div className="grid gap-8 md:grid-cols-2">
        <div className="card aspect-square overflow-hidden">
          {image?.url ? <img src={image.url} alt={product.name} width={800} height={800} className="h-full w-full object-cover" fetchPriority="high" />
            : <div aria-hidden className="flex h-full items-center justify-center text-6xl text-muted">◻︎</div>}
        </div>
        <div>
          <p className="text-muted">{product.brand}</p>
          <h1 className="text-3xl font-bold">{product.name}</h1>
          <p className="mt-2 text-2xl font-semibold">{formatMoney(product.price)}</p>
          <Form method="post" className="mt-6 flex items-end gap-3">
            <Csrf token={root?.csrf} />
            <label className="block">
              <span className="text-sm">Quantity</span>
              <input name="quantity" type="number" min={1} max={99} defaultValue={1} required className="field mt-1 w-24" />
            </label>
            <button className="btn-primary" type="submit" disabled={adding}>{adding ? 'Adding…' : 'Add to cart'}</button>
          </Form>
          <div aria-live="polite" className="mt-3">
            {actionData?.ok && <p className="text-ok">Added to your cart. <Link to="/cart" className="underline">View cart</Link></p>}
            {actionData && !actionData.ok && <ProblemNotice problem={actionData.problem} />}
          </div>
          {product.description && <p className="mt-6 whitespace-pre-line">{product.description}</p>}
          {product.attributes && Object.keys(product.attributes).length > 0 && (
            <dl className="mt-6 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
              {Object.entries(product.attributes).map(([k, v]) => (
                <div key={k} className="contents"><dt className="text-muted">{k}</dt><dd>{String(v)}</dd></div>
              ))}
            </dl>
          )}
          <p className="mt-4 text-sm text-muted">SKU {product.sku}</p>
        </div>
      </div>

      <section aria-labelledby="reviews" className="mt-12">
        <div className="flex items-baseline justify-between">
          <h2 id="reviews" className="text-xl font-semibold">Reviews</h2>
          <Link to={`/p/${product.id}/review`} className="underline">Write a review</Link>
        </div>
        <Suspense fallback={<div className="skeleton mt-3 h-24" />}>
          <Await resolve={reviews}>
            {(r) => r.ok ? (
              <>
                <p className="mt-2 text-muted">
                  {r.value.summary?.count ? `${r.value.summary.average?.toFixed(1)} out of 5 from ${r.value.summary.count} ${r.value.summary.count === 1 ? 'review' : 'reviews'}` : 'No reviews yet.'}
                </p>
                <ul className="mt-4 space-y-4">
                  {(r.value.items ?? []).map((rv) => (
                    <li key={rv.id} className="card p-4">
                      <p><span aria-label={`${rv.rating} out of 5`}>{'★'.repeat(rv.rating ?? 0)}{'☆'.repeat(5 - (rv.rating ?? 0))}</span> <strong>{rv.title}</strong></p>
                      {rv.verified && <p className="text-sm text-ok">Verified purchase</p>}
                      {rv.body && <p className="mt-2 whitespace-pre-line">{rv.body}</p>}
                    </li>
                  ))}
                </ul>
              </>
            ) : <p role="status" className="mt-2 text-muted">Reviews are temporarily unavailable.</p>}
          </Await>
        </Suspense>
      </section>

      <Suspense fallback={null}>
        <Await resolve={recommended}>
          {(r) => r.ok && r.value.length > 0 ? (
            <section aria-labelledby="also" className="mt-12">
              <h2 id="also" className="mb-3 text-xl font-semibold">Often bought together</h2>
              <ProductGrid label="Often bought together">{r.value.map((p) => <ProductCard key={p.id} p={p} />)}</ProductGrid>
            </section>
          ) : null}
        </Await>
      </Suspense>
    </article>
  );
}
