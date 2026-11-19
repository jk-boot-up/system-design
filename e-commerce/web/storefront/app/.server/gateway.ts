import createClient from 'openapi-fetch';
import type { paths as CartPaths } from '../api/cart';
import type { paths as CatalogPaths } from '../api/catalog';
import type { paths as OrderPaths } from '../api/order';
import type { paths as ReviewPaths } from '../api/review';
import type { paths as SearchPaths } from '../api/search';
import { ApiError, type Problem } from '../lib/problem.ts';

/**
 * Typed calls to the API gateway on behalf of one shopper (design/16 §16.4). Only these fixed, contract-typed routes are
 * reachable: callers cannot make the storefront fetch an arbitrary URL. The bearer token, guest id, request id and the
 * shopper's address (for the gateway's per-address rate limits) are added to every call.
 */
export interface Caller {
  token?: string;
  guestId?: string;
  requestId: string;
  clientIp?: string;
}

const TIMEOUT_MS = 8_000;
const CHECKOUT_TIMEOUT_MS = 15_000;   // the gateway allows the checkout saga 12 s

export function gateway(baseUrl: string, caller: Caller) {
  const headers: Record<string, string> = { 'X-Request-Id': caller.requestId, Accept: 'application/json, application/problem+json' };
  if (caller.token) headers.Authorization = `Bearer ${caller.token}`;
  if (caller.guestId) headers['X-Guest-Id'] = caller.guestId;
  if (caller.clientIp) headers['X-Real-IP'] = caller.clientIp;
  const timed = (ms: number) => (req: Request) => fetch(req, { signal: AbortSignal.timeout(ms) });
  const options = { baseUrl, headers, fetch: timed(TIMEOUT_MS) };
  return {
    catalog: createClient<CatalogPaths>(options),
    search: createClient<SearchPaths>(options),
    cart: createClient<CartPaths>(options),
    review: createClient<ReviewPaths>(options),
    // order-service's contract is relative to /api/v1
    order: createClient<OrderPaths>({ ...options, baseUrl: `${baseUrl}/api/v1` }),
    checkout: createClient<OrderPaths>({ ...options, baseUrl: `${baseUrl}/api/v1`, fetch: timed(CHECKOUT_TIMEOUT_MS) }),
    /** recommendation-service has no OpenAPI contract yet: a small hand-written call. */
    async recommendations(sku: string, limit = 8): Promise<Recommendation[]> {
      const res = await timed(TIMEOUT_MS)(new Request(`${baseUrl}/api/v1/products/${encodeURIComponent(sku)}/recommendations?limit=${limit}`, { headers }));
      if (!res.ok) throw await ApiError.from(res);
      const body = (await res.json()) as { items?: Recommendation[] };
      return body.items ?? [];
    },
  };
}

export type Gateway = ReturnType<typeof gateway>;

export interface Recommendation {
  sku: string;
  score?: number;
}

/**
 * Unwraps an openapi-fetch result: the data, or an ApiError carrying the problem's code, traceId and field errors.
 * Network failures and timeouts become a 503 DEPENDENCY_UNAVAILABLE.
 */
export async function call<T>(request: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  let result: { data?: T; error?: unknown; response: Response };
  try {
    result = await request;
  } catch (e) {
    throw new ApiError({ status: 503, code: 'DEPENDENCY_UNAVAILABLE', detail: e instanceof Error ? e.name : 'network' });
  }
  const { response } = result;
  if (response.ok) return result.data as T;
  const problem = (typeof result.error === 'object' && result.error !== null ? result.error : {}) as Partial<Problem>;
  throw new ApiError({
    ...problem,
    status: response.status,
    code: problem.code ?? (response.status === 404 ? 'NOT_FOUND' : 'UNEXPECTED'),
    retryAfter: Number(response.headers.get('Retry-After')) || undefined,
  });
}
