const LABELS: Record<string, { text: string; tone: string }> = {
  PENDING: { text: 'Confirming your payment', tone: 'text-warn' },
  CONFIRMED: { text: 'Confirmed', tone: 'text-ok' },
  CANCELLING: { text: 'Cancelling', tone: 'text-warn' },
  CANCELLED: { text: 'Cancelled', tone: 'text-muted' },
  SHIPPED: { text: 'Shipped', tone: 'text-ok' },
  DELIVERED: { text: 'Delivered', tone: 'text-ok' },
};

export function OrderStatus({ status }: { status: string | undefined }) {
  const label = LABELS[status ?? ''] ?? { text: status ?? 'Unknown', tone: 'text-muted' };
  return <span className={`font-medium ${label.tone}`}>{label.text}</span>;
}

export const formatDate = (iso: string | undefined): string =>
  iso ? new Intl.DateTimeFormat('en-IE', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date(iso)) + ' UTC' : '';
