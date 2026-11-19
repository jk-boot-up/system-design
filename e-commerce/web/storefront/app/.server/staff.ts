import { data, redirect, type RouterContextProvider } from 'react-router';
import { runtime } from './runtime.ts';
import { requireUser } from './shop.ts';

const SECOND_FACTORS = new Set(['otp', 'hwk', 'swk', 'mfa']);
const MAX_AGE_SECONDS = 12 * 60 * 60;

/**
 * Staff pages (design/16 §16.7): role `staff`, and a sign-in with a second factor within the last 12 hours. The gateway
 * and catalog-service check the role again on every call; this only decides what the pages show.
 */
export function requireStaff(context: Readonly<RouterContextProvider>, request: Request) {
  const user = requireUser(context, request, 'staff');
  const cfg = runtime().config;
  if (cfg.STAFF_REQUIRE_SECOND_FACTOR) {
    const strong = user.amr.some((m) => SECOND_FACTORS.has(m));
    const recent = Date.now() / 1000 - user.authTime < MAX_AGE_SECONDS;
    if (!strong) throw data('Staff pages need a sign-in with a second factor (one-time code or passkey). Set one up in your account, then sign in again.', { status: 403 });
    if (!recent) throw redirect(`/login?returnTo=${encodeURIComponent(new URL(request.url).pathname)}`);
  }
  return user;
}
