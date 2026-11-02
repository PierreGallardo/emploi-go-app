import tailwindcss from '@tailwindcss/vite';
import { reactRouter } from '@react-router/dev/vite';
import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { devSessionPlugin } from './tooling/dev-session-plugin.ts';
import { mapAssetsPlugin } from './tooling/map-assets-plugin.ts';
import { prerenderedPagesPlugin } from './tooling/prerendered-pages-plugin.ts';
import { cspPreviewPlugin } from './tooling/csp-preview-plugin.ts';

const proxy = {
  '/api': {
    target: process.env.API_TARGET ?? 'http://localhost:8080',
    changeOrigin: true,
  },
};

export default defineConfig({
  plugins: [
    devSessionPlugin(),
    mapAssetsPlugin(),
    prerenderedPagesPlugin(),
    cspPreviewPlugin(),
    tailwindcss(),
    reactRouter(),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    strictPort: true,
    proxy,
  },
  preview: { proxy },
});
