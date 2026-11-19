import type { Config } from '@react-router/dev/config';

// Server rendering on Node; every page is rendered by the storefront (the backend-for-frontend), design/16 §16.3.
export default {
  ssr: true,
  appDirectory: 'app',
  buildDirectory: 'build',
} satisfies Config;
