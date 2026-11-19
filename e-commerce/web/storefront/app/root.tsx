import { useEffect, useRef } from 'react';
import {
  Form, isRouteErrorResponse, Link, Links, Meta, NavLink, Outlet, Scripts, ScrollRestoration, useLocation, useNavigation,
  useRouteLoaderData,
} from 'react-router';
import type { Route } from './+types/root';
import { categoryTree } from './.server/catalog.ts';
import { shop, shopMiddleware } from './.server/shop.ts';
import { messageFor, type Problem } from './lib/problem.ts';
import './app.css';

export const middleware: Route.MiddlewareFunction[] = [shopMiddleware];

export async function loader({ context }: Route.LoaderArgs) {
  const s = shop(context);
  const [categories, cartCount] = await Promise.all([
    categoryTree(s.api).catch(() => []),
    s.user || s.guestId(false)
      ? s.api.cart.GET('/api/v1/cart').then((r) => r.data?.lines?.reduce((n, l) => n + (l.quantity ?? 0), 0) ?? 0, () => 0)
      : Promise.resolve(0),
  ]);
  let flash: string | undefined;
  if (s.session?.data.flash) {
    flash = s.session.data.flash;
    delete s.session.data.flash;
    s.session.dirty = true;
  }
  return {
    user: s.user ? { name: s.user.name, staff: s.user.roles.includes('staff') } : undefined,
    csrf: s.csrf,
    categories: categories.map((c) => ({ name: c.name, slug: c.slug })),
    cartCount,
    flash,
  };
}

export type RootData = Awaited<ReturnType<typeof loader>>;

export function useRootData(): RootData | undefined {
  return useRouteLoaderData<RootData>('root');
}

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        <Meta />
        <Links />
      </head>
      <body className="min-h-screen">
        {children}
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

export default function App() {
  const data = useRootData();
  return (
    <>
      <a href="#main" className="skip-link">Skip to content</a>
      <Header data={data} />
      <ProgressBar />
      <main id="main" className="mx-auto max-w-6xl px-4 py-6">
        {data?.flash && <p role="status" className="notice mb-4 border-ok text-ok">{data.flash}</p>}
        <FocusHeadingOnNavigate />
        <Outlet />
      </main>
      <footer className="mx-auto max-w-6xl px-4 py-10 text-sm text-muted">
        Demo shop. Card payments use test tokens; no real money moves.
      </footer>
    </>
  );
}

function Header({ data }: { data: RootData | undefined }) {
  const location = useLocation();
  const returnTo = location.pathname + location.search;
  return (
    <header className="border-b border-line bg-surface">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-4 py-3">
        <Link to="/" className="text-xl font-bold text-brand">Shop</Link>
        <Form method="get" action="/search" role="search" className="order-last flex w-full gap-2 sm:order-none sm:w-auto sm:flex-1">
          <label htmlFor="q" className="sr-only">Search products</label>
          <input id="q" name="q" type="search" placeholder="Search products" maxLength={200} className="field" />
          <button className="btn-quiet" type="submit">Search</button>
        </Form>
        <nav aria-label="Account" className="ml-auto flex items-center gap-3">
          {data?.user?.staff && <NavLink to="/staff" className="hover:underline">Staff</NavLink>}
          {data?.user && <NavLink to="/orders" className="hover:underline">Orders</NavLink>}
          <NavLink to="/cart" className="hover:underline">
            Cart{data && data.cartCount > 0 && <span className="ml-1 rounded-full bg-brand px-2 text-sm text-brand-ink">{data.cartCount}<span className="sr-only"> items</span></span>}
          </NavLink>
          {data?.user ? (
            <Form method="post" action="/logout">
              <input type="hidden" name="_csrf" value={data.csrf} />
              <button className="btn-quiet" type="submit">Sign out</button>
            </Form>
          ) : (
            <Link to={`/login?returnTo=${encodeURIComponent(returnTo)}`} className="btn-primary" reloadDocument>Sign in</Link>
          )}
        </nav>
      </div>
      {data && data.categories.length > 0 && (
        <nav aria-label="Categories" className="mx-auto flex max-w-6xl gap-4 overflow-x-auto px-4 pb-2 text-sm">
          {data.categories.map((c) => (
            <NavLink key={c.slug} to={`/c/${c.slug}`} className={({ isActive }) => (isActive ? 'font-semibold text-brand' : 'text-muted hover:text-ink')}>
              {c.name}
            </NavLink>
          ))}
        </nav>
      )}
    </header>
  );
}

function ProgressBar() {
  const busy = useNavigation().state !== 'idle';
  return <div aria-hidden className={`h-0.5 bg-brand transition-opacity ${busy ? 'opacity-100 animate-pulse' : 'opacity-0'}`} />;
}

/** Moves focus to the new page's heading after client-side navigation, as a full page load would (WCAG 2.4.3). */
function FocusHeadingOnNavigate() {
  const { pathname } = useLocation();
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    const h1 = document.querySelector<HTMLElement>('main h1');
    if (h1) {
      h1.tabIndex = -1;
      h1.focus();
    }
  }, [pathname]);
  return null;
}

export function ErrorBoundary({ error }: Route.ErrorBoundaryProps) {
  let title = 'Something went wrong';
  let message = 'Please try again in a moment.';
  if (isRouteErrorResponse(error)) {
    if (error.status === 404) {
      title = 'Page not found';
      message = 'The page you are looking for does not exist.';
    } else if (error.status === 403) {
      title = 'No access';
      message = typeof error.data === 'string' ? error.data : messageFor({ code: 'FORBIDDEN' });
    } else if (error.status === 429) {
      title = 'Slow down';
      message = messageFor({ code: 'RATE_LIMITED', retryAfter: (error.data as Problem | undefined)?.retryAfter });
    } else if (error.status === 503) {
      title = 'Temporarily unavailable';
      message = messageFor({ code: 'DEPENDENCY_UNAVAILABLE' });
    }
    const traceId = (error.data as Problem | undefined)?.traceId;
    if (error.status >= 500 && traceId) message += ` Reference: ${traceId}`;
  }
  return (
    <main id="main" className="mx-auto max-w-2xl px-4 py-16">
      <h1 className="text-2xl font-bold">{title}</h1>
      <p className="mt-2 text-muted">{message}</p>
      <p className="mt-6"><a href="/" className="btn-primary">Go to the shop</a></p>
    </main>
  );
}
