import { index, layout, prefix, route, type RouteConfig } from '@react-router/dev/routes';

// Pages and BFF endpoints of design/16 §16.7.
export default [
  index('routes/home.tsx'),
  route('c/:slug', 'routes/category.tsx'),
  route('search', 'routes/search.tsx'),
  route('p/:id', 'routes/product.tsx'),
  route('p/:id/review', 'routes/review.tsx'),
  route('cart', 'routes/cart.tsx'),
  route('checkout', 'routes/checkout.tsx'),
  route('orders', 'routes/orders.tsx'),
  route('orders/:id', 'routes/order.tsx'),
  ...prefix('staff', [layout('routes/staff/layout.tsx', [
    index('routes/staff/home.tsx'),
    route('products/new', 'routes/staff/product-new.tsx'),
    route('products/:id', 'routes/staff/product-edit.tsx'),
    route('categories', 'routes/staff/categories.tsx'),
    route('stock', 'routes/staff/stock.tsx'),
  ])]),
  route('login', 'routes/auth/login.ts'),
  route('auth/callback', 'routes/auth/callback.ts'),
  route('logout', 'routes/auth/logout.ts'),
  route('auth/backchannel-logout', 'routes/auth/backchannel-logout.ts'),
  route('*', 'routes/not-found.tsx'),
] satisfies RouteConfig;
