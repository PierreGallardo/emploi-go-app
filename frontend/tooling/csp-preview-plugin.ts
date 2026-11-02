import { resolve } from 'node:path';
import type { Plugin } from 'vite';
import { buildPolicy } from './csp.ts';

/** `vite preview`, which the E2E tests use, sends the policy that Caddy sends: a violation shows up in the tests. */
export function cspPreviewPlugin(): Plugin {
  return {
    name: 'emploi-go-csp-preview',
    async configurePreviewServer(server) {
      // React Router prerenders through a preview server of its own, before the pages and their policy exist.
      if (process.env.IS_RR_BUILD_REQUEST === 'yes') return;
      const policy = await buildPolicy(
        resolve(server.config.root, server.config.build.outDir),
      );
      server.middlewares.use((_request, response, next) => {
        response.setHeader('Content-Security-Policy', policy);
        next();
      });
    },
  };
}
