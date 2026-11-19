import { isRouteErrorResponse } from 'react-router';
import { describe, expect, it } from 'vitest';
import { contentSecurityPolicy } from '../app/.server/headers.ts';
import { imageUrl } from '../app/lib/images.ts';
import { formatMoney } from '../app/lib/money.ts';
import { fieldErrors, KNOWN_CODES, messageFor, publicProblem, ApiError } from '../app/lib/problem.ts';

describe('money', () => {
  it('shows the decimal string as given, never via floats', () => {
    expect(formatMoney({ amount: '89.90', currency: 'EUR' })).toBe('€89.90');
    expect(formatMoney({ amount: '1234567.05', currency: 'EUR' })).toBe('€1,234,567.05');
    expect(formatMoney({ amount: '0.1', currency: 'EUR' })).toBe('€0.10');
    expect(formatMoney({ amount: '19', currency: 'EUR' })).toBe('€19.00');
    expect(formatMoney(undefined)).toBe('');
  });
});

describe('problems', () => {
  it('has words for every error code in the API catalogue', () => {
    for (const code of ['VALIDATION_FAILED', 'UNAUTHENTICATED', 'FORBIDDEN', 'NOT_FOUND', 'OUT_OF_STOCK', 'NOT_CANCELLABLE',
      'ALREADY_REVIEWED', 'ILLEGAL_STATE', 'PAYMENT_DECLINED', 'VERSION_MISMATCH', 'PRECONDITION_REQUIRED', 'CART_NOT_READY',
      'UPLOAD_MISSING', 'RATE_LIMITED', 'DEPENDENCY_UNAVAILABLE', 'PAYMENT_UNKNOWN', 'SEARCH_UNAVAILABLE']) {
      expect(KNOWN_CODES).toContain(code);
    }
    expect(messageFor({ code: 'SOMETHING_NEW' })).toBe('Something went wrong.');
    expect(messageFor({ code: 'RATE_LIMITED', retryAfter: 5 })).toContain('5 seconds');
  });

  it('gives a page the upstream status (not 500) when an API error escapes a loader', () => {
    const e = new ApiError({ status: 503, code: 'DEPENDENCY_UNAVAILABLE', detail: 'circuit open for catalog', traceId: 't-1' });
    expect(isRouteErrorResponse(e)).toBe(true);
    expect(e.status).toBe(503);
    expect(e.data).toEqual({ status: 503, code: 'DEPENDENCY_UNAVAILABLE', traceId: 't-1', errors: undefined, retryAfter: undefined });
  });

  it('never passes upstream detail to the browser', () => {
    const e = new ApiError({ status: 409, code: 'OUT_OF_STOCK', detail: 'SELECT * FROM stock…', traceId: 't-123456' });
    expect(publicProblem(e)).not.toHaveProperty('detail', expect.anything());
    expect(fieldErrors({ status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'title', message: 'too long' }] })).toEqual({ title: 'too long' });
  });
});

describe('content security policy', () => {
  it('allows only nonce scripts and our own origins in production', () => {
    const csp = contentSecurityPolicy('n0nce', { dev: false, imageOrigin: 'https://cdn.example', idOrigin: 'https://id.example' });
    expect(csp).toContain("script-src 'nonce-n0nce' 'strict-dynamic'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("base-uri 'none'");
    expect(csp).toContain('form-action \'self\' https://id.example');
    expect(csp).not.toContain('unsafe-eval');
    expect(csp).not.toMatch(/script-src[^;]*unsafe-inline/);
  });
});

describe('images', () => {
  it('builds URLs from object keys and keeps full URLs', () => {
    expect(imageUrl('https://cdn.example/img/', 'products/a b.jpg')).toBe('https://cdn.example/img/products/a%20b.jpg');
    expect(imageUrl('https://cdn.example', 'https://other/x.jpg')).toBe('https://other/x.jpg');
    expect(imageUrl('https://cdn.example', null)).toBeUndefined();
  });
});
