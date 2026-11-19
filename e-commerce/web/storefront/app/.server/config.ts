import { z } from 'zod';

/**
 * Settings from the environment, checked once at start-up. The same image runs everywhere; nothing here is baked into
 * the build (design/16 §16.12). A missing or malformed value stops the process with a clear message.
 */
const schema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  PORT: z.coerce.number().int().default(8090),
  /** The origin shoppers use, e.g. https://shop.example. CSRF checks and redirect URIs are built from it. */
  PUBLIC_ORIGIN: z.url().transform((u) => new URL(u).origin),
  /** The API gateway inside the cluster. Pages call it with the shopper's token; browsers never do. */
  GATEWAY_URL: z.url(),
  /** OpenID Connect issuer as it appears in tokens (the public Keycloak URL). */
  OIDC_ISSUER: z.url(),
  /** Optional in-cluster origin for the server-to-Keycloak calls when the issuer's host is not reachable from inside. */
  OIDC_BACKCHANNEL_ORIGIN: z.url().optional(),
  OIDC_CLIENT_ID: z.string().min(1).default('shop-storefront'),
  OIDC_CLIENT_SECRET: z.string().min(16),
  /** Plain-HTTP issuer allowed: only for the local stack. */
  OIDC_ALLOW_HTTP: z.stringbool().default(false),
  REDIS_URL: z.url(),
  /** Session encryption keys, newest first: "kid:base64-32-bytes,kid:…". Old keys only decrypt, so keys rotate without sign-outs. */
  SESSION_ENCRYPTION_KEYS: z.string().min(1),
  /** Images: object keys are served from here (the CDN in production, object storage locally). */
  IMAGE_BASE_URL: z.url().default('http://localhost:9000/product-images'),
  /** Where the server sends image uploads when the image host is not reachable from inside (local stack only). */
  OBJECT_STORAGE_INTERNAL_ORIGIN: z.url().optional(),
  /** Staff pages require a second factor at sign-in (Keycloak `amr` contains otp, hwk or swk). Off only in the local stack. */
  STAFF_REQUIRE_SECOND_FACTOR: z.stringbool().default(true),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
});

export type Config = z.infer<typeof schema>;

function load(): Config {
  const parsed = schema.safeParse(process.env);
  if (!parsed.success) {
    const problems = parsed.error.issues.map((i) => `  ${i.path.join('.')}: ${i.message}`).join('\n');
    throw new Error(`Invalid storefront configuration:\n${problems}`);
  }
  if (parsed.data.NODE_ENV === 'production' && parsed.data.OIDC_ALLOW_HTTP && !parsed.data.OIDC_ISSUER.startsWith('http://localhost')) {
    throw new Error('OIDC_ALLOW_HTTP is only for a local issuer');
  }
  return parsed.data;
}

let cached: Config | undefined;
/** Loaded on first use so that tests can set the environment first. */
export function config(): Config {
  cached ??= load();
  return cached;
}
