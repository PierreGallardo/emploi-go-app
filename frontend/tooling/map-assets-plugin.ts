import { stat } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import type { Connect, Plugin } from 'vite';

export function mapAssetsMiddleware(
  directory: string,
): Connect.NextHandleFunction {
  const assetRoot = resolve(directory, 'cartes');
  return (request, response, next) => {
    let pathname: string;
    try {
      pathname = decodeURIComponent((request.url ?? '').split('?')[0]!);
    } catch {
      response.statusCode = 400;
      response.end();
      return;
    }
    if (!pathname.startsWith('/cartes/')) {
      next();
      return;
    }
    if (!['GET', 'HEAD'].includes(request.method ?? '')) {
      response.statusCode = 405;
      response.end();
      return;
    }
    const target = resolve(directory, `.${pathname}`);
    const notFound = () => {
      response.statusCode = 404;
      response.setHeader('Content-Type', 'text/plain; charset=utf-8');
      response.end('Ressource cartographique introuvable.');
    };
    if (!target.startsWith(assetRoot + sep)) {
      notFound();
      return;
    }
    void stat(target)
      .then((file) => {
        if (!file.isFile()) {
          notFound();
          return;
        }
        // Vite's static middleware implements Range; never let a missing tile reach the SPA fallback.
        response.setHeader(
          'Cache-Control',
          'public, max-age=31536000, immutable',
        );
        next();
      })
      .catch((error: NodeJS.ErrnoException) => {
        if (error.code === 'ENOENT' || error.code === 'ENOTDIR') notFound();
        else {
          response.statusCode = 503;
          response.setHeader('Content-Type', 'text/plain; charset=utf-8');
          response.end('Ressource cartographique temporairement indisponible.');
        }
      });
  };
}

export function mapAssetsPlugin(): Plugin {
  return {
    name: 'emploi-go-map-assets',
    configureServer(server) {
      server.middlewares.use(mapAssetsMiddleware(server.config.publicDir));
    },
    configurePreviewServer(server) {
      server.middlewares.use(
        mapAssetsMiddleware(
          resolve(server.config.root, server.config.build.outDir),
        ),
      );
    },
  };
}
