import { expect, request, test, type APIRequestContext, type APIResponse } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { env } from './env.js';

export type Money = { amount: string; currency: string };
export type Problem = { type?: string; title?: string; status: number; detail?: string; code?: string; traceId?: string };

/** The test users of the dev realm (deploy/compose/keycloak/shop-realm.json, deploy/k8s components/test-users). */
export type User = 'ada' | 'bob' | 'sam';

const tokens = new Map<User, { value: string; expires: number }>();

export async function token(user: User): Promise<string> {
  const cached = tokens.get(user);
  if (cached && cached.expires > Date.now() + 30_000) return cached.value;
  const ctx = await request.newContext();
  const res = await ctx.post(env.tokenUrl, {
    form: { grant_type: 'password', client_id: env.clientId, username: user, password: user },
  });
  expect(res.status(), `token for ${user}: ${await res.text()}`).toBe(200);
  const body = (await res.json()) as { access_token: string; expires_in: number };
  await ctx.dispose();
  tokens.set(user, { value: body.access_token, expires: Date.now() + body.expires_in * 1000 });
  return body.access_token;
}

/** An API client through the edge, signed in as `user`, or anonymous. */
export class Api {
  private constructor(readonly http: APIRequestContext, private readonly user?: User) {}

  static async as(user?: User, headers: Record<string, string> = {}): Promise<Api> {
    const http = await request.newContext({
      baseURL: env.edge,
      ignoreHTTPSErrors: env.insecureTls,
      extraHTTPHeaders: { accept: 'application/json', ...headers },
    });
    return new Api(http, user);
  }

  private async headers(extra: Record<string, string> = {}): Promise<Record<string, string>> {
    return this.user ? { authorization: `Bearer ${await token(this.user)}`, ...extra } : extra;
  }

  async get(path: string, headers?: Record<string, string>): Promise<APIResponse> {
    return this.http.get(path, { headers: await this.headers(headers) });
  }
  async post(path: string, data?: unknown, headers?: Record<string, string>): Promise<APIResponse> {
    return this.http.post(path, { data, headers: await this.headers(headers) });
  }
  async put(path: string, data?: unknown, headers?: Record<string, string>): Promise<APIResponse> {
    return this.http.put(path, { data, headers: await this.headers(headers) });
  }
  async delete(path: string, headers?: Record<string, string>): Promise<APIResponse> {
    return this.http.delete(path, { headers: await this.headers(headers) });
  }

  /** Expects `status` and returns the parsed body; on a mismatch the failure shows the whole response. */
  static async json<T>(res: APIResponse, status: number): Promise<T> {
    const text = await res.text();
    expect(res.status(), `${res.url()} → ${res.status()} ${text}`).toBe(status);
    return (text ? JSON.parse(text) : undefined) as T;
  }

  /** Expects an RFC 9457 problem with this status and code. */
  static async problem(res: APIResponse, status: number, code: string): Promise<Problem> {
    const p = await Api.json<Problem>(res, status);
    expect(res.headers()['content-type']).toContain('application/problem+json');
    expect(p.code, JSON.stringify(p)).toBe(code);
    expect(p.traceId, 'every problem carries a trace id').toBeTruthy();
    return p;
  }

  async dispose(): Promise<void> {
    await this.http.dispose();
  }
}

/** Polls until `check` returns a value (not undefined) or the time is up: for effects that travel through Kafka. */
export async function eventually<T>(what: string, check: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown;
  for (let delay = 250; ; delay = Math.min(delay * 2, 2_000)) {
    try {
      const value = await check();
      if (value !== undefined) return value;
    } catch (e) {
      last = e;
    }
    if (Date.now() > deadline) throw new Error(`timed out after ${timeoutMs} ms waiting for: ${what}${last ? ` (last error: ${last})` : ''}`);
    await new Promise((r) => setTimeout(r, delay));
  }
}

/**
 * Skips the current test when an optional part of the stack is not running (see env.require). A required profile gets up
 * to a minute to answer, since some stores (ClickHouse) have no health check of their own and may still be starting.
 */
export async function requireProfile(profile: string, healthUrl: string): Promise<void> {
  const mandatory = env.require.has(profile) || env.require.has('all');
  const ctx = await request.newContext({ ignoreHTTPSErrors: true });
  const deadline = Date.now() + (mandatory ? 60_000 : 0);
  let up = false;
  do {
    up = await ctx.get(healthUrl, { timeout: 3_000 }).then((r) => r.ok(), () => false);
    if (!up && Date.now() < deadline) await new Promise((r) => setTimeout(r, 2_000));
  } while (!up && Date.now() < deadline);
  await ctx.dispose();
  if (!up && mandatory) throw new Error(`profile "${profile}" is required (E2E_REQUIRE) but ${healthUrl} is not answering`);
  test.skip(!up, `profile "${profile}" is not running (${healthUrl})`);
}

export const uniqueId = (prefix: string): string => `${prefix}-${Date.now().toString(36)}-${randomUUID().slice(0, 6)}`.toUpperCase();

/** The `sub` claim (customer id) of a user's access token. */
export async function customerId(user: User): Promise<string> {
  const payload = (await token(user)).split('.')[1] ?? '';
  return (JSON.parse(Buffer.from(payload, 'base64url').toString()) as { sub: string }).sub;
}
