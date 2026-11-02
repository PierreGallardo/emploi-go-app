import assert from 'node:assert/strict';
import test from 'node:test';
import { runBlocks } from './check.ts';

test('un bloc en échec laisse les autres finir et seul son nom est rapporté', async () => {
  const finished: string[] = [];
  const messages: string[] = [];
  const failed = await runBlocks(
    {
      frontend: [
        async () => {
          throw new Error('lint failed');
        },
      ],
      backend: [
        async () => finished.push('backend:1'),
        async () => finished.push('backend:2'),
      ],
    },
    ['frontend', 'backend'],
    { log: (message) => messages.push(message), now: () => 0 },
  );
  assert.deepEqual(failed, ['frontend']);
  assert.deepEqual(finished, ['backend:1', 'backend:2']);
  assert.ok(
    messages.some(
      (message) =>
        message.startsWith('✗ frontend') && message.includes('lint failed'),
    ),
  );
  assert.ok(messages.includes('✓ backend   0s'));
});

test('un bloc inconnu est refusé avant de lancer quoi que ce soit', async () => {
  let ran = false;
  await assert.rejects(
    runBlocks({ backend: [async () => (ran = true)] }, ['backend', 'fronted'], {
      log: () => {},
    }),
    /Unknown check: fronted/,
  );
  assert.equal(ran, false);
});

test('une étape en échec arrête son bloc, une annulation n’est pas comptée comme un échec', async () => {
  const ran: string[] = [];
  const failed = await runBlocks(
    {
      backend: [
        async () => {
          throw new Error('compile');
        },
        async () => ran.push('tests'),
      ],
    },
    ['backend'],
    { log: () => {}, now: () => 0 },
  );
  assert.deepEqual(failed, ['backend']);
  assert.deepEqual(ran, []);
  const controller = new AbortController();
  const messages: string[] = [];
  await assert.rejects(
    runBlocks(
      {
        frontend: [
          async ({ signal }) => {
            controller.abort(new Error('interrupted'));
            signal.throwIfAborted();
          },
        ],
      },
      ['frontend'],
      { signal: controller.signal, log: (message) => messages.push(message) },
    ),
    /interrupted/,
  );
  assert.deepEqual(messages, ['Running frontend…']);
});
