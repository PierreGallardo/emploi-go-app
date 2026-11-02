import { createServer } from 'node:http';
import {
  createHash,
  createSign,
  generateKeyPairSync,
  randomUUID,
} from 'node:crypto';
import type { AddressInfo } from 'node:net';

// Owned by the E2E runner: signed OIDC responses and PKCE verification, never real Google.
export async function startGoogleFixture(redirectUri: string) {
  const callback = new URL(redirectUri);
  if (
    callback.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(callback.hostname)
  )
    throw new Error('A local fixture callback is required.');
  const { privateKey, publicKey } = generateKeyPairSync('rsa', {
    modulusLength: 2048,
  });
  const key = {
    ...publicKey.export({ format: 'jwk' }),
    kid: 'fixture',
    alg: 'RS256',
    use: 'sig',
  };
  type Identity = {
    email: string;
    subject: string;
    verified: boolean;
    hd: string | null;
    cancel: boolean;
  };
  let identity: Identity = {
    email: 'fixture@gmail.com',
    subject: 'fixture',
    verified: true,
    hd: null,
    cancel: false,
  };
  const codes = new Map<
    string,
    { nonce: string; challenge: string; identity: Identity }
  >();
  let origin = '';
  const b64 = (value: unknown) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');
  const server = createServer((request, response) => {
    void handle().catch(() => {
      response.writeHead(400);
      response.end('{}');
    });
    async function handle() {
      const url = new URL(request.url ?? '/', origin);
      let body = '';
      for await (const chunk of request) {
        body += String(chunk);
        if (Buffer.byteLength(body) > 16_384)
          throw new Error('Fixture request too large.');
      }
      response.setHeader('Cache-Control', 'no-store');
      response.setHeader('Content-Type', 'application/json');
      if (url.pathname === '/fixture' && request.method === 'POST') {
        const value: unknown = JSON.parse(body);
        if (!value || typeof value !== 'object')
          throw new Error('Invalid fixture identity.');
        for (const field of ['email', 'subject'] as const)
          if (field in value) {
            const item = (value as Record<string, unknown>)[field];
            if (typeof item !== 'string' || !item || item.length > 320)
              throw new Error('Invalid fixture identity.');
            identity = { ...identity, [field]: item };
          }
        for (const field of ['verified', 'cancel'] as const)
          if (field in value) {
            const item = (value as Record<string, unknown>)[field];
            if (typeof item !== 'boolean')
              throw new Error('Invalid fixture identity.');
            identity = { ...identity, [field]: item };
          }
        if ('hd' in value) {
          if (value.hd !== null && typeof value.hd !== 'string')
            throw new Error('Invalid fixture domain.');
          identity = { ...identity, hd: value.hd };
        }
        response.end('{}');
        return;
      }
      if (url.pathname === '/jwks' && request.method === 'GET') {
        response.end(JSON.stringify({ keys: [key] }));
        return;
      }
      if (url.pathname === '/authorize' && request.method === 'GET') {
        const nonce = url.searchParams.get('nonce'),
          challenge = url.searchParams.get('code_challenge'),
          state = url.searchParams.get('state');
        if (
          !nonce ||
          !challenge ||
          !state ||
          url.searchParams.get('redirect_uri') !== redirectUri ||
          url.searchParams.get('client_id') !== 'fixture-client' ||
          url.searchParams.get('code_challenge_method') !== 'S256'
        )
          throw new Error('Invalid fixture OIDC request.');
        const code = randomUUID();
        codes.set(code, { nonce, challenge, identity: { ...identity } });
        if (codes.size > 200) codes.delete(codes.keys().next().value!);
        const target = new URL(callback);
        target.searchParams.set('state', state);
        target.searchParams.set(
          identity.cancel ? 'error' : 'code',
          identity.cancel ? 'access_denied' : code,
        );
        response.writeHead(302, { Location: target.href });
        response.end();
        return;
      }
      if (url.pathname === '/token' && request.method === 'POST') {
        const parameters = new URLSearchParams(body),
          code = parameters.get('code') ?? '',
          authorization = codes.get(code);
        codes.delete(code);
        const challenge = createHash('sha256')
          .update(parameters.get('code_verifier') ?? '')
          .digest('base64url');
        if (
          !authorization ||
          authorization.challenge !== challenge ||
          parameters.get('redirect_uri') !== redirectUri ||
          parameters.get('client_id') !== 'fixture-client' ||
          parameters.get('client_secret') !== 'fixture-secret'
        ) {
          response.writeHead(400);
          response.end('{"error":"invalid_grant"}');
          return;
        }
        const now = Math.floor(Date.now() / 1000),
          owner = authorization.identity;
        const unsigned = `${b64({ alg: 'RS256', kid: 'fixture' })}.${b64({ iss: origin, sub: owner.subject, aud: 'fixture-client', iat: now - 5, exp: now + 300, nonce: authorization.nonce, email: owner.email, email_verified: owner.verified, ...(owner.hd ? { hd: owner.hd } : {}) })}`;
        const signer = createSign('RSA-SHA256');
        signer.update(unsigned);
        response.end(
          JSON.stringify({
            access_token: 'fixture-access',
            token_type: 'Bearer',
            expires_in: 300,
            scope: 'openid email',
            id_token: `${unsigned}.${signer.sign(privateKey, 'base64url')}`,
          }),
        );
        return;
      }
      response.writeHead(404);
      response.end('{}');
    }
  });
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return {
    url: origin,
    stop: () =>
      new Promise<void>((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections();
      }),
  };
}
