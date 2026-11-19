import { NavLink, Outlet } from 'react-router';
import type { Route } from './+types/layout';
import { requireStaff } from '../../.server/staff.ts';

export const headers: Route.HeadersFunction = () => ({ 'Cache-Control': 'private, no-store' });
export const meta: Route.MetaFunction = () => [{ name: 'robots', content: 'noindex' }];

export function loader({ request, context }: Route.LoaderArgs) {
  requireStaff(context, request);
  return null;
}

/** Staff area: code split by route, so shoppers never download it. */
export default function StaffLayout() {
  const link = ({ isActive }: { isActive: boolean }) => (isActive ? 'font-semibold text-brand' : 'hover:underline');
  return (
    <div className="grid gap-6 md:grid-cols-[12rem_1fr]">
      <nav aria-label="Staff" className="flex flex-col gap-2">
        <NavLink end to="/staff" className={link}>Overview</NavLink>
        <NavLink to="/staff/products/new" className={link}>New product</NavLink>
        <NavLink to="/staff/categories" className={link}>Categories</NavLink>
        <NavLink to="/staff/stock" className={link}>Stock</NavLink>
      </nav>
      <div><Outlet /></div>
    </div>
  );
}
