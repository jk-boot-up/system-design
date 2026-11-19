import { reactRouter } from '@react-router/dev/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [tailwindcss(), reactRouter()],
  build: {
    assetsInlineLimit: 0,   // no data: URIs for scripts or fonts; images stay files the CSP allows from 'self'
    sourcemap: 'hidden',    // maps for error reports, never served to browsers
  },
  server: { port: 5173, strictPort: true },
});
