/**
 * Money arrives as a decimal string ("89.90") with a currency and is shown as it is: never parsed into a float, so a
 * price is never off by a cent (design/16 §16.13).
 */
export interface Money {
  amount?: string;
  currency?: string;
}

export function formatMoney(m: Money | undefined, locale = 'en-IE'): string {
  if (!m?.amount || !m.currency) return '';
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(m.amount);
  if (!match) return `${m.amount} ${m.currency}`;
  const [, sign, whole, frac = ''] = match;
  // Intl formats the integer part (grouping, symbol position); the decimals are appended as given.
  const parts = new Intl.NumberFormat(locale, { style: 'currency', currency: m.currency, minimumFractionDigits: 2 }).formatToParts(Number(whole));
  return sign + parts.map((p) => (p.type === 'fraction' ? frac.padEnd(2, '0') : p.value)).join('');
}
