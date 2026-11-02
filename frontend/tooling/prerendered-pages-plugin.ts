import { existsSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import type { Plugin } from 'vite';

/** `vite preview` looks for `/connexion.html`, while React Router writes `/connexion/index.html`: like Caddy, the preview serves the prerendered page. */
export function prerenderedPagesPlugin(): Plugin {
  return {
    name: 'emploi-go-prerendered-pages',
    configurePreviewServer(server) {
      const root = resolve(server.config.root, server.config.build.outDir);
      server.middlewares.use((request, _response, next) => {
        const url = request.url ?? '/';
        const queryStart = url.indexOf('?');
        const path = queryStart === -1 ? url : url.slice(0, queryStart);
        const query = queryStart === -1 ? '' : url.slice(queryStart);
        let pathname: string;
        try {
          pathname = decodeURIComponent(path);
        } catch {
          next();
          return;
        }
        const page = resolve(root, `.${pathname}`, 'index.html');
        if (
          pathname !== '/' &&
          pathname !== '/api' &&
          !pathname.startsWith('/api/') &&
          !/\.[^/]*$/.test(pathname) &&
          page.startsWith(root + sep) &&
          existsSync(page)
        )
          request.url = `${path.replace(/\/?$/, '/')}index.html${query}`;
        next();
      });
    },
  };
}
