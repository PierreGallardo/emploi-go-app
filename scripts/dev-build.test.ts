import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  readFile,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import {
  matchesGeneration,
  publishGeneration,
  resetClasses,
  triggerName,
  generationName,
  waitForGeneration,
} from './dev-build.ts';
import type { ManagedProcess } from './dev-process.ts';

async function fixture(action: (directory: string) => Promise<void>) {
  const directory = await mkdtemp(
    join(tmpdir(), 'emploi-go-publication-test-'),
  );
  try {
    await action(directory);
  } finally {
    assert.equal(dirname(resolve(directory)), resolve(tmpdir()));
    await rm(directory, { recursive: true, force: true });
  }
}

async function put(path: string, content: string) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, content);
}

test('publication remplace les classes et ressources supprimées sans garder une ancienne génération', async () => {
  await fixture(async (directory) => {
    const build = join(directory, 'build');
    const runtime = join(directory, 'runtime');
    await put(join(build, 'classes', 'Old.class'), 'old');
    await put(
      join(build, 'classes', 'obsolete', 'application.yml'),
      'old resource',
    );
    await put(join(build, 'dev-classpath.txt'), 'dependency-a.jar');
    const first = await publishGeneration(directory);
    const firstTime = (await stat(join(runtime, 'classes', triggerName)))
      .mtimeMs;
    await resetClasses(build);
    assert.equal(
      await readFile(join(runtime, 'classes', 'Old.class'), 'utf8'),
      'old',
    );
    await put(join(build, 'classes', 'New.class'), 'new');
    await put(join(build, 'classes', 'application.yml'), 'new resource');
    await put(join(build, 'dev-classpath.txt'), 'dependency-b.jar');
    const second = await publishGeneration(directory);
    assert.notEqual(second, first);
    await assert.rejects(stat(join(runtime, 'classes', 'Old.class')), {
      code: 'ENOENT',
    });
    await assert.rejects(stat(join(runtime, 'classes', 'obsolete')), {
      code: 'ENOENT',
    });
    assert.equal(
      await readFile(join(runtime, 'classes', 'New.class'), 'utf8'),
      'new',
    );
    assert.equal(
      await readFile(join(runtime, 'classes', generationName), 'utf8'),
      second,
    );
    assert.equal(
      await readFile(join(runtime, 'classes', triggerName), 'utf8'),
      second,
    );
    assert.equal(
      await readFile(join(runtime, 'dev-classpath.txt'), 'utf8'),
      'dependency-b.jar',
    );
    assert.ok(
      (await stat(join(runtime, 'classes', triggerName))).mtimeMs > firstTime,
    );
  });
});

test('readiness exige la génération et le PID courants', () => {
  assert.equal(
    matchesGeneration({ generation: 'new', pid: 42 }, 'new', 42),
    true,
  );
  for (const value of [
    null,
    {},
    { generation: 'old', pid: 42 },
    { generation: 'new', pid: 41 },
    { generation: 'new' },
  ]) {
    assert.equal(matchesGeneration(value, 'new', 42), false);
  }
});

test('une ancienne readiness ne débloque pas la publication du contrat', async () => {
  await fixture(async (directory) => {
    const path = join(directory, 'ready.json');
    await writeFile(path, JSON.stringify({ generation: 'old', pid: 42 }));
    const child = {
      pid: 42,
      exitCode: null,
      signalCode: null,
    } as ManagedProcess;
    let finished = false;
    const waiting = waitForGeneration(path, 'new', child, {
      timeoutMs: 3000,
    }).then(() => {
      finished = true;
    });
    await delay(350);
    assert.equal(finished, false);
    await writeFile(path, JSON.stringify({ generation: 'new', pid: 42 }));
    await waiting;
    assert.equal(finished, true);
  });
});

test('readiness échoue si le processus est arrêté ou la session annulée', async () => {
  const child = { pid: 42, exitCode: 1, signalCode: null } as ManagedProcess;
  await assert.rejects(waitForGeneration('unused', 'new', child), /stopped/);
  Object.assign(child, { exitCode: null });
  await assert.rejects(
    waitForGeneration('unused', 'new', child, {
      signal: AbortSignal.abort(new Error('cancelled')),
    }),
    /cancelled/,
  );
});

test('une ressource peut devenir dossier puis redevenir fichier', async () => {
  await fixture(async (directory) => {
    const source = join(directory, 'build', 'classes', 'asset');
    const destination = join(directory, 'runtime', 'classes', 'asset');
    await put(source, 'file');
    await put(join(directory, 'build', 'dev-classpath.txt'), 'dependencies');
    await publishGeneration(directory);
    await rm(source);
    await put(join(source, 'nested.txt'), 'nested');
    await publishGeneration(directory);
    assert.equal(
      await readFile(join(destination, 'nested.txt'), 'utf8'),
      'nested',
    );
    await rm(source, { recursive: true });
    await put(source, 'file again');
    await publishGeneration(directory);
    assert.equal(await readFile(destination, 'utf8'), 'file again');
  });
});
