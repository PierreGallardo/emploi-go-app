import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { acquireLock, lockPath } from './lock.ts';

const quiet = { log: () => {} };

async function withDirectory(action: (directory: string) => Promise<void>) {
  const directory = await mkdtemp(join(tmpdir(), 'emploi-go-lock-test-'));
  try {
    await action(directory);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test('chaque dossier a son verrou, libéré une seule fois par son propriétaire', async () => {
  assert.notEqual(
    lockPath('backend', '/a/target'),
    lockPath('backend', '/a/target-dev'),
  );
  assert.equal(
    lockPath('backend', '/a/target'),
    lockPath('backend', '/a/./target'),
  );
  await withDirectory(async (directory) => {
    const path = join(directory, 'owned.lock');
    const lock = await acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      ...quiet,
    });
    const owner = JSON.parse(await readFile(path, 'utf8'));
    assert.equal(owner.pid, process.pid);
    assert.equal(owner.command, 'npm test');
    await lock.release();
    await lock.release();
    const next = await acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      ...quiet,
    });
    next.releaseSync();
  });
});

test('un verrou abandonné par une commande morte est repris sans intervention', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'stale.lock');
    await writeFile(
      path,
      JSON.stringify({ pid: 999_999, token: 'dead', command: 'npm run dev' }),
    );
    await utimes(path, new Date(0), new Date(0));
    const lock = await acquireLock(path, {
      command: 'npm run dev',
      whenBusy: 'replace',
      ...quiet,
    });
    assert.equal(JSON.parse(await readFile(path, 'utf8')).pid, process.pid);
    await lock.release();
  });
});

test('une commande attend la fin du propriétaire vivant au lieu d’échouer', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'busy.lock');
    const first = await acquireLock(path, {
      command: 'npm run check',
      whenBusy: 'wait',
      ...quiet,
    });
    const messages: string[] = [];
    const second = await acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      log: (message) => messages.push(message),
      pause: () => first.release(),
    });
    assert.deepEqual(messages, [
      `Waiting for npm run check (PID ${process.pid}) to finish…`,
    ]);
    await second.release();
  });
});

test('une nouvelle session dev demande à la précédente de s’arrêter et prend sa place', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'dev.lock');
    let stopRequests = 0;
    let previous: Awaited<ReturnType<typeof acquireLock>> | undefined;
    previous = await acquireLock(path, {
      command: 'dev session',
      whenBusy: 'replace',
      heartbeatMs: 5,
      onStopRequest: () => {
        stopRequests++;
        void previous?.release();
      },
      ...quiet,
    });
    const messages: string[] = [];
    const next = await acquireLock(path, {
      command: 'dev session',
      whenBusy: 'replace',
      log: (message) => messages.push(message),
      pause: () => new Promise((resolve) => setImmediate(resolve)),
    });
    const owner = JSON.parse(await readFile(path, 'utf8'));
    assert.equal(stopRequests, 1);
    assert.deepEqual(messages, [
      `Stopping the previous dev session (PID ${process.pid})…`,
    ]);
    await assert.rejects(readFile(`${path}.stop`), { code: 'ENOENT' });
    await next.release();
    await assert.rejects(readFile(path), { code: 'ENOENT' });
    assert.notEqual(owner.token, undefined);
  });
});

test('une session qui ne répond pas à la demande d’arrêt fait échouer le remplacement', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'stuck.lock');
    const stuck = await acquireLock(path, {
      command: 'dev session',
      whenBusy: 'replace',
      ...quiet,
    });
    let clock = 0;
    await assert.rejects(
      acquireLock(path, {
        command: 'dev session',
        whenBusy: 'replace',
        replaceTimeoutMs: 1000,
        staleMs: Number.MAX_SAFE_INTEGER,
        now: () => clock,
        pause: async () => {
          clock += 600;
        },
        ...quiet,
      }),
      /did not stop\. Close it, then try again\./,
    );
    await stuck.release();
  });
});

test('la libération ne supprime jamais le verrou repris par une autre commande', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'taken.lock');
    const lock = await acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      ...quiet,
    });
    const other = JSON.stringify({ pid: 1, token: 'other', command: 'x' });
    await writeFile(path, other);
    await lock.release();
    assert.equal(await readFile(path, 'utf8'), other);
  });
});

test('une attente annulée s’arrête sans prendre le verrou', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'cancel.lock');
    const owner = await acquireLock(path, {
      command: 'npm run check',
      whenBusy: 'wait',
      ...quiet,
    });
    const before = await readFile(path, 'utf8');
    await assert.rejects(
      acquireLock(path, {
        command: 'npm test',
        whenBusy: 'wait',
        signal: AbortSignal.abort(new Error('cancelled')),
        ...quiet,
      }),
      /cancelled/,
    );
    const controller = new AbortController();
    const waiting = acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      signal: controller.signal,
      ...quiet,
    });
    controller.abort(new Error('cancelled while waiting'));
    await assert.rejects(waiting, /cancelled while waiting/);
    assert.equal(await readFile(path, 'utf8'), before);
    await owner.release();
  });
});

test('un verrou au PID vivant mais figé, comme après une réutilisation du PID, finit par être repris', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'reused.lock');
    await writeFile(
      path,
      JSON.stringify({ pid: process.pid, token: 'old', command: 'npm test' }),
    );
    let clock = 0;
    const lock = await acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      reusedPidMs: 1000,
      now: () => clock,
      pause: async () => {
        clock += 600;
      },
      ...quiet,
    });
    assert.notEqual(JSON.parse(await readFile(path, 'utf8')).token, 'old');
    await lock.release();
  });
});

test('un propriétaire vivant mais suspendu, comme après Ctrl+Z, garde son verrou', async () => {
  await withDirectory(async (directory) => {
    const path = join(directory, 'suspended.lock');
    await writeFile(
      path,
      JSON.stringify({
        pid: process.pid,
        token: 'owner',
        command: 'npm run check',
      }),
    );
    let clock = 0;
    let pauses = 0;
    const lock = await acquireLock(path, {
      command: 'npm test',
      whenBusy: 'wait',
      staleMs: 1000,
      now: () => clock,
      pause: async () => {
        clock += 60_000;
        if (++pauses === 3) await rm(path);
      },
      ...quiet,
    });
    assert.equal(pauses, 3);
    await lock.release();
  });
});
