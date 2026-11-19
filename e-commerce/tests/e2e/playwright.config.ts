import { defineConfig } from '@playwright/test';
import { env } from './src/env.js';

// Journeys share one stack and the gateway's per-user checkout rate limit, so they run one at a time.
export default defineConfig({
  testDir: './journeys',
  workers: 1,
  fullyParallel: false,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: env.edge,
    ignoreHTTPSErrors: env.insecureTls,
    extraHTTPHeaders: { accept: 'application/json' },
  },
});
