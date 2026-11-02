import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startLocalMailbox } from './local-mailbox.ts';

test('boîte locale : acceptation idempotente, refus de conflit et protection contre un autre site', async () => {
  const mailbox = await startLocalMailbox(0);
  try {
    const send = (text: string) =>
      fetch(`${mailbox.url}/emails`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'fixture-operation',
        },
        body: JSON.stringify({
          to: ['jeune@example.invalid'],
          subject: 'Inscription de test',
          text,
        }),
      });
    assert.equal((await send('Courriel fictif')).status, 200);
    assert.equal((await send('Courriel fictif')).status, 200);
    assert.equal(mailbox.messages().length, 1);
    assert.equal((await send('Autre contenu')).status, 409);
    assert.equal(
      (
        await fetch(`${mailbox.url}/messages`, {
          headers: { Origin: 'https://example.invalid' },
        })
      ).status,
      403,
    );
    const page = await fetch(mailbox.url);
    assert.equal(page.headers.get('referrer-policy'), 'no-referrer');
    assert.equal(page.headers.get('x-frame-options'), 'DENY');
  } finally {
    await mailbox.stop();
  }
});
