import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { startGoogleFixture } from './google-fixture.ts';

test('le fournisseur Google fictif refuse les callbacks externes', async () => {
  await assert.rejects(
    startGoogleFixture('https://outside.invalid/callback'),
    /local fixture callback is required/,
  );
});
test('le fournisseur Google fictif vérifie PKCE et consomme le code après une erreur', async () => {
  const redirect = 'http://127.0.0.1:4173/api/auth/google/callback/google';
  const fixture = await startGoogleFixture(redirect);
  try {
    const parameters = new URLSearchParams({
      client_id: 'fixture-client',
      redirect_uri: redirect,
      state: 'fixture-state',
      nonce: 'fixture-nonce',
      code_challenge_method: 'S256',
      code_challenge: createHash('sha256')
        .update('fixture-verifier')
        .digest('base64url'),
    });
    const response = await fetch(`${fixture.url}/authorize?${parameters}`, {
      redirect: 'manual',
    });
    assert.equal(response.status, 302);
    const code = new URL(response.headers.get('location')!).searchParams.get(
      'code',
    )!;
    const token = (verifier: string) =>
      fetch(`${fixture.url}/token`, {
        method: 'POST',
        body: new URLSearchParams({
          code,
          client_id: 'fixture-client',
          client_secret: 'fixture-secret',
          redirect_uri: redirect,
          code_verifier: verifier,
        }),
      });
    assert.equal((await token('wrong')).status, 400);
    assert.equal((await token('fixture-verifier')).status, 400);
  } finally {
    await fixture.stop();
  }
});
