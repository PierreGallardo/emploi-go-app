import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { waitForGeneration } from './dev-build.ts';
import { waitForHealth, type ManagedProcess } from './dev-process.ts';

test('une correction des sources interrompt readiness et santé avant le timeout', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'emploi-go-recovery-test-'));
  const child = { pid: 42, exitCode: null, signalCode: null } as ManagedProcess;
  let calls = 0;
  const start = Date.now();
  try {
    await assert.rejects(
      waitForGeneration(join(directory, 'absent.json'), 'generation', child, {
        timeoutMs: 300_000,
        isCurrent: async () => ++calls < 2,
      }),
      /Sources changed/,
    );
    assert.ok(Date.now() - start < 5000);
    await assert.rejects(
      waitForHealth('http://127.0.0.1:1/actuator/health', {
        child,
        isCurrent: async () => false,
      }),
      /Sources changed/,
    );
  } finally {
    assert.equal(dirname(resolve(directory)), resolve(tmpdir()));
    await rm(directory, { recursive: true, force: true });
  }
});

test('readiness remonte immédiatement la fin ou erreur de lancement JVM', async () => {
  for (const createCompletion of [
    () => Promise.resolve({ code: 1, signal: null }),
    () => Promise.reject(new Error('java introuvable')),
  ]) {
    const completion = createCompletion();
    const child = {
      pid: 42,
      exitCode: null,
      signalCode: null,
      completion,
    } as ManagedProcess;
    await assert.rejects(
      waitForGeneration(
        join(tmpdir(), 'absent-readiness.json'),
        'generation',
        child,
        {
          timeoutMs: 300_000,
        },
      ),
      /stopped|java introuvable/,
    );
  }
});
