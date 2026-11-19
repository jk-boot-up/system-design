import { data, Form, Link, redirect, useNavigation } from 'react-router';
import { z } from 'zod';
import type { Route } from './+types/review';
import { call } from '../.server/gateway.ts';
import { PRIVATE } from '../.server/pages.ts';
import { requireUser, shop } from '../.server/shop.ts';
import { Csrf, ProblemNotice } from '../components/Notice.tsx';
import { ApiError, fieldErrors, publicProblem } from '../lib/problem.ts';
import { useRootData } from '../root.tsx';

export const meta: Route.MetaFunction = () => [{ title: 'Write a review – Shop' }, { name: 'robots', content: 'noindex' }];
export const headers: Route.HeadersFunction = () => PRIVATE;

async function product(context: Route.LoaderArgs['context'], id: string) {
  if (!z.uuid().safeParse(id).success) throw data('Not found', { status: 404 });
  try {
    return await call(shop(context).api.catalog.GET('/api/v1/products/{id}', { params: { path: { id } } }));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) throw data('Not found', { status: 404 });
    throw e;
  }
}

export async function loader({ request, params, context }: Route.LoaderArgs) {
  requireUser(context, request);
  const p = await product(context, params.id);
  return { id: params.id, name: p.name };
}

// Same limits as review-service; plain text only (React escapes it when shown).
const Review = z.object({
  rating: z.coerce.number().int().min(1).max(5),
  title: z.string().trim().min(1).max(120),
  body: z.string().trim().max(4000).optional(),
});

export async function action({ request, params, context }: Route.ActionArgs) {
  requireUser(context, request);
  const parsed = Review.safeParse(Object.fromEntries(await request.formData()));
  if (!parsed.success) {
    const errors = parsed.error.issues.map((i) => ({ field: String(i.path[0]), message: i.message }));
    return data({ problem: { status: 400, code: 'VALIDATION_FAILED', errors } }, { status: 400 });
  }
  const p = await product(context, params.id);
  const s = shop(context);
  try {
    await call(s.api.review.POST('/api/v1/products/{sku}/reviews', { params: { path: { sku: p.sku! } }, body: parsed.data }));
  } catch (e) {
    if (e instanceof ApiError) return data({ problem: publicProblem(e) }, { status: e.status });
    throw e;
  }
  s.session!.data.flash = 'Thank you for your review.';
  s.session!.dirty = true;
  throw redirect(`/p/${params.id}`);
}

export default function WriteReview({ loaderData, actionData }: Route.ComponentProps) {
  const root = useRootData();
  const busy = useNavigation().state !== 'idle';
  const errors = fieldErrors(actionData?.problem);
  return (
    <>
      <h1 className="text-2xl font-bold">Review {loaderData.name}</h1>
      <p className="mt-1"><Link to={`/p/${loaderData.id}`} className="underline">Back to the product</Link></p>
      {actionData?.problem && <ProblemNotice className="mt-4" problem={actionData.problem} />}
      <Form method="post" className="mt-6 max-w-xl space-y-4" noValidate>
        <Csrf token={root?.csrf} />
        <fieldset aria-describedby={errors.rating ? 'rating-error' : undefined}>
          <legend className="font-semibold">Rating</legend>
          <div className="flex gap-4">
            {[5, 4, 3, 2, 1].map((n) => <label key={n} className="flex items-center gap-1"><input type="radio" name="rating" value={n} required /> {n} ★</label>)}
          </div>
          {errors.rating && <p id="rating-error" className="text-sm text-danger">Choose a rating</p>}
        </fieldset>
        <label className="block">
          <span className="font-semibold">Title</span>
          <input name="title" maxLength={120} required className="field mt-1" aria-invalid={Boolean(errors.title)} aria-describedby={errors.title ? 'title-error' : undefined} />
          {errors.title && <span id="title-error" className="text-sm text-danger">Give your review a title</span>}
        </label>
        <label className="block">
          <span className="font-semibold">Your review <span className="font-normal text-muted">(optional)</span></span>
          <textarea name="body" maxLength={4000} rows={6} className="field mt-1" />
        </label>
        <button className="btn-primary" type="submit" disabled={busy}>Submit review</button>
      </Form>
    </>
  );
}
