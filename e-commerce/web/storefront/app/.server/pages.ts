import { ApiError, publicProblem, type Problem } from '../lib/problem.ts';
import { runtime } from './runtime.ts';

/**
 * Public pages for signed-out visitors may be kept by the edge for 30 s (design/16 §16.7): `X-Accel-Expires` is read
 * and removed by NGINX; browsers revalidate. The middleware turns this off for anyone with a session or cart.
 */
export const PUBLIC_CACHE = { 'Cache-Control': 'public, max-age=0, s-maxage=30, stale-while-revalidate=60', 'X-Accel-Expires': '30' };
export const PRIVATE = { 'Cache-Control': 'private, no-store' };

/** Runs a call whose failure should degrade one section of a page, not the page. */
export async function section<T>(work: Promise<T>): Promise<{ ok: true; value: T } | { ok: false; problem: Problem }> {
  try {
    return { ok: true, value: await work };
  } catch (e) {
    if (e instanceof ApiError) return { ok: false, problem: publicProblem(e) };
    runtime().log.error({ err: e instanceof Error ? e.message : String(e) }, 'section failed');
    return { ok: false, problem: { status: 500, code: 'INTERNAL_ERROR' } };
  }
}

export const imageBase = (): string => runtime().config.IMAGE_BASE_URL;
