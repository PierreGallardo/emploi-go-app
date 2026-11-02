import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';

interface LocalMessage {
  id: string;
  to: string[];
  subject: string;
  text: string;
}
const escapeHtml = (value: string) =>
  value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');

export async function startLocalMailbox(port: number) {
  const messages = new Map<string, LocalMessage>();
  const keys = new Map<string, { payload: string; id: string }>();
  const server = createServer((request, response) => {
    void handle(request, response).catch(() => {
      response.writeHead(400);
      response.end('Requête invalide.');
    });
  });
  let actualPort = port;
  async function handle(request: IncomingMessage, response: ServerResponse) {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('X-Frame-Options', 'DENY');
    response.setHeader(
      'Content-Security-Policy',
      "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'",
    );
    const hosts = [`127.0.0.1:${actualPort}`, `localhost:${actualPort}`];
    if (
      !hosts.includes(request.headers.host ?? '') ||
      request.headers.origin ||
      request.headers['sec-fetch-site'] === 'cross-site'
    ) {
      response.writeHead(403);
      response.end('Accès local uniquement.');
      return;
    }
    if (request.method === 'POST' && request.url === '/emails') {
      let payload = '';
      for await (const chunk of request) {
        payload += chunk.toString();
        if (Buffer.byteLength(payload) > 32_768) {
          response.writeHead(413);
          response.end();
          return;
        }
      }
      const body: unknown = JSON.parse(payload);
      const key = request.headers['idempotency-key'];
      if (
        !body ||
        typeof body !== 'object' ||
        !('to' in body) ||
        !Array.isArray(body.to) ||
        body.to.length !== 1 ||
        typeof body.to[0] !== 'string' ||
        !('subject' in body) ||
        typeof body.subject !== 'string' ||
        !('text' in body) ||
        typeof body.text !== 'string' ||
        typeof key !== 'string' ||
        !key
      ) {
        response.writeHead(400);
        response.end();
        return;
      }
      const previous = keys.get(key);
      if (previous && previous.payload !== payload) {
        response.writeHead(409, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ name: 'invalid_idempotent_request' }));
        return;
      }
      const id = previous?.id ?? randomUUID();
      if (!previous) {
        if (messages.size >= 200) {
          response.writeHead(429);
          response.end();
          return;
        }
        messages.set(id, {
          id,
          to: body.to as string[],
          subject: body.subject,
          text: body.text,
        });
        keys.set(key, { payload, id });
      }
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ id }));
      return;
    }
    if (request.method === 'GET' && request.url === '/messages') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify([...messages.values()]));
      return;
    }
    if (request.method === 'GET' && request.url === '/') {
      const items = [...messages.values()]
        .reverse()
        .map((message) => {
          const match = message.text.match(/https?:\/\/[^\s]+/);
          const url = match ? new URL(match[0]) : null;
          const href =
            url && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
              ? `<p><a href="${escapeHtml(url.href)}">Ouvrir la confirmation</a></p>`
              : '';
          return `<article><h2>${escapeHtml(message.subject)}</h2><p>À : ${escapeHtml(message.to.join(', '))}</p><pre>${escapeHtml(message.text)}</pre>${href}</article>`;
        })
        .join('');
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      response.end(
        `<!doctype html><html lang="fr"><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Courriels locaux Emploi Go</title><style>body{font:1rem sans-serif;max-width:60rem;margin:auto;padding:1rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}article{border:1px solid #888;padding:1rem;margin:1rem 0}a{display:inline-block;padding:.75rem}</style></head><body><h1>Courriels locaux Emploi Go</h1><p>Aucun envoi externe. Recharge cette page pour voir les nouveaux messages. Ils disparaissent à l’arrêt.</p>${items || '<p>Aucun message reçu.</p>'}</body></html>`,
      );
      return;
    }
    response.writeHead(404);
    response.end();
  }
  await new Promise<void>((accept, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      const address = server.address();
      if (address && typeof address === 'object') actualPort = address.port;
      accept();
    });
  });
  return {
    url: `http://127.0.0.1:${actualPort}`,
    messages: () => [...messages.values()],
    stop: () =>
      new Promise<void>((accept, reject) => {
        server.close((error) => (error ? reject(error) : accept()));
        server.closeAllConnections();
      }),
  };
}
