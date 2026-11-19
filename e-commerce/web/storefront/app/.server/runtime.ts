import { createClient } from 'redis';
import { config, type Config } from './config.ts';
import { createLogger, type Logger } from './log.ts';
import { Oidc } from './oidc.ts';
import { SessionStore, type RedisClient } from './session.ts';

/**
 * Process-wide objects, created on first use: settings, logger, the `redis-session` connection, the session store and
 * the OpenID Connect client. server.ts asks for readiness and shutdown through the server build (entry.server.tsx).
 */
export interface Runtime {
  config: Config;
  log: Logger;
  redis: RedisClient;
  sessions: SessionStore;
  oidc: Oidc;
}

let current: Runtime | undefined;

export function runtime(): Runtime {
  if (current) return current;
  const cfg = config();
  const log = createLogger(cfg.LOG_LEVEL);
  const redis = createClient({
    url: cfg.REDIS_URL,
    socket: { connectTimeout: 3_000, reconnectStrategy: (retries) => Math.min(retries * 200, 3_000) },
    commandOptions: { timeout: 2_000 },
  } as Parameters<typeof createClient>[0]);
  redis.on('error', (e: Error) => log.warn({ err: e.message }, 'redis-session error'));
  redis.connect().catch((e: Error) => log.error({ err: e.message }, 'redis-session connect failed'));
  current = { config: cfg, log, redis, sessions: new SessionStore(redis, cfg.SESSION_ENCRYPTION_KEYS), oidc: new Oidc(cfg) };
  return current;
}

/** Ready when sessions can be read and written. Keycloak is not required: browsing works without it. */
export async function ready(): Promise<boolean> {
  try {
    return (await runtime().redis.ping()) === 'PONG';
  } catch {
    return false;
  }
}

export async function shutdown(): Promise<void> {
  if (!current) return;
  await current.redis.close().catch(() => undefined);
  current = undefined;
}
