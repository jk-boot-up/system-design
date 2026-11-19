import { createHash } from 'node:crypto';
import { randomId } from './crypto.ts';
import type { Session } from './session.ts';

/**
 * The server owns the checkout idempotency key (design/16 §16.8). It is kept in the session next to a hash of the cart:
 * a double click, reload or retry sends the same key and order-service returns the first result. A changed cart gets
 * a new key; a finished order clears it.
 */
export interface CartLike {
  lines?: readonly { sku?: string; quantity?: number; unitPrice?: { amount?: string; currency?: string } }[];
  total?: { amount?: string; currency?: string };
}

export function cartHash(cart: CartLike): string {
  const lines = [...(cart.lines ?? [])].map((l) => `${l.sku}:${l.quantity}:${l.unitPrice?.amount}`).sort();
  return createHash('sha256').update(`${lines.join('|')}#${cart.total?.amount}${cart.total?.currency}`).digest('base64url');
}

/** The key for this cart: the stored one if the cart is unchanged, otherwise a new one (43 chars, within the API's 64). */
export function checkoutKey(session: Session, cart: CartLike): string {
  const hash = cartHash(cart);
  if (session.data.checkout?.cartHash !== hash) {
    session.data.checkout = { key: randomId(), cartHash: hash };
    session.dirty = true;
  }
  return session.data.checkout.key;
}

export function finishCheckout(session: Session): void {
  delete session.data.checkout;
  session.dirty = true;
}
