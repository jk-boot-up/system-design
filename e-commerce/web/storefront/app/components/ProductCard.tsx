import { Link } from 'react-router';
import { formatMoney, type Money } from '../lib/money.ts';

export interface CardProduct {
  id?: string;
  sku?: string;
  name?: string;
  brand?: string;
  price?: Money;
  image?: string;
  inStock?: boolean;
}

export function ProductCard({ p }: { p: CardProduct }) {
  return (
    <li className="card flex flex-col overflow-hidden">
      <div className="aspect-square bg-canvas">
        {p.image ? (
          <img src={p.image} alt="" width={400} height={400} loading="lazy" decoding="async" className="h-full w-full object-cover" />
        ) : (
          <div aria-hidden className="flex h-full items-center justify-center text-4xl text-muted">◻︎</div>
        )}
      </div>
      <div className="flex flex-1 flex-col gap-1 p-3">
        <span className="text-sm text-muted">{p.brand}</span>
        <Link to={`/p/${p.id}`} className="font-medium hover:underline">{p.name}</Link>
        <span className="mt-auto font-semibold">{formatMoney(p.price)}</span>
        {p.inStock === false && <span className="text-sm text-warn">Out of stock</span>}
      </div>
    </li>
  );
}

export function ProductGrid({ children, label }: { children: React.ReactNode; label: string }) {
  return <ul aria-label={label} className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">{children}</ul>;
}

export function GridSkeleton({ count = 8 }: { count?: number }) {
  return (
    <ul aria-hidden className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
      {Array.from({ length: count }, (_, i) => <li key={i} className="skeleton aspect-[3/4]" />)}
    </ul>
  );
}
