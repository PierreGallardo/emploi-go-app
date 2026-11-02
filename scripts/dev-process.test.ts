import { errorMessage } from './errors.ts';
import type { AddressInfo } from 'node:net';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  readFile,
  rename,
  rm,
  writeFile,
} from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import {
  ensureLocalEnv,
  isPortFree,
  runCommand,
  spawnManaged,
  stopProcess,
} from './dev-process.ts';
import { sourceFingerprint } from './dev-build.ts';

test(
  'stopProcess arrête uniquement son arbre de processus, y compris un enfant',
  { timeout: 30_000 },
  async () => {
    const unrelated = spawnManaged(
      process.execPath,
      ['-e', 'setInterval(()=>{},1000)'],
      { stdio: 'ignore' },
    );
    const parent = spawnManaged(
      process.execPath,
      [
        '-e',
        `
    const {spawn}=require('node:child_process');
    const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'inherit'});
    console.log(child.pid);
    setInterval(()=>{},1000);
  `,
      ],
      { stdio: ['ignore', 'pipe', 'pipe'] },
    );
    try {
      const childPid = await new Promise<number>((resolvePid, reject) => {
        parent.stdout!.once('data', (data) =>
          resolvePid(Number(data.toString().trim())),
        );
        parent.once('error', reject);
      });
      assert.ok(Number.isInteger(childPid) && childPid > 0);
      await stopProcess(parent);
      await delay(200);
      assert.throws(() => process.kill(childPid, 0), { code: 'ESRCH' });
      assert.doesNotThrow(() => process.kill(unrelated.pid!, 0));
    } finally {
      await stopProcess(parent);
      await stopProcess(unrelated);
    }
  },
);

test(
  'une commande bloquée est interrompue et échoue, une sortie normale est capturée',
  { timeout: 30_000 },
  async () => {
    const result = await runCommand(
      process.execPath,
      ['-e', 'console.log("ready")'],
      { capture: true },
    );
    assert.equal(result.stdout.trim(), 'ready');
    await assert.rejects(
      runCommand(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {
        capture: true,
        timeoutMs: 200,
      }),
      /failed/,
    );
  },
);

test('un port occupé en IPv6 seulement, comme par Vite, n’est pas libre', async () => {
  const server = createServer();
  await new Promise<void>((resolveListen) =>
    server.listen(0, '::1', resolveListen),
  );
  try {
    assert.equal(
      await isPortFree((server.address() as AddressInfo).port),
      false,
    );
  } finally {
    await new Promise<void>((resolveClose) =>
      server.close(() => resolveClose()),
    );
  }
});

test('un port occupé est signalé sans arrêter son serveur', async () => {
  const server = createServer();
  await new Promise<void>((resolveListen) =>
    server.listen(0, '127.0.0.1', resolveListen),
  );
  const port = (server.address() as AddressInfo).port;
  try {
    assert.equal(await isPortFree(port), false);
    assert.equal(server.listening, true);
  } finally {
    await new Promise<void>((resolveClose, reject) =>
      server.close((error) => (error ? reject(error) : resolveClose())),
    );
  }
  assert.equal(await isPortFree(port), true);
});

test('les erreurs conservent le diagnostic et masquent les secrets, une annulation ne lance rien', async () => {
  const secret = 'fictional-password-for-process-test';
  await assert.rejects(
    runCommand(
      process.execPath,
      [
        '-e',
        'console.error("diagnostic " + process.env.SCAFFOLD_PASSWORD); process.exit(1)',
      ],
      {
        capture: true,
        env: { ...process.env, SCAFFOLD_PASSWORD: secret },
      },
    ),
    (error) =>
      errorMessage(error).includes('diagnostic [hidden]') &&
      !errorMessage(error).includes(secret),
  );
  await assert.rejects(
    runCommand(process.execPath, ['-e', 'process.exit(0)'], {
      signal: AbortSignal.abort(new Error('cancel-before-spawn')),
    }),
    /cancel-before-spawn/,
  );
});

test('la surveillance détecte renommages, suppressions, ressources et pom sans surveiller target', async () => {
  const owned = await mkdtemp(join(tmpdir(), 'emploi-go-dev-test-'));
  assert.ok(
    resolve(owned).startsWith(
      resolve(tmpdir()) + (process.platform === 'win32' ? '\\' : '/'),
    ),
  );
  try {
    const sources = join(owned, 'src', 'main');
    await mkdir(join(sources, 'java'), { recursive: true });
    await mkdir(join(sources, 'resources'));
    await mkdir(join(owned, 'target'));
    await writeFile(join(owned, 'pom.xml'), '<project/>');
    await writeFile(join(sources, 'java', 'Before.java'), 'class Before {}');
    const before = await sourceFingerprint(owned);
    await writeFile(join(owned, 'target', 'ignored.class'), 'output');
    assert.equal(await sourceFingerprint(owned), before);
    await rename(
      join(sources, 'java', 'Before.java'),
      join(sources, 'java', 'After.java'),
    );
    const renamed = await sourceFingerprint(owned);
    assert.notEqual(renamed, before);
    await rm(join(sources, 'java', 'After.java'));
    const deleted = await sourceFingerprint(owned);
    assert.notEqual(deleted, renamed);
    await writeFile(
      join(sources, 'resources', 'application.yml'),
      'changed: true',
    );
    const resource = await sourceFingerprint(owned);
    assert.notEqual(resource, deleted);
    await writeFile(
      join(owned, 'pom.xml'),
      '<project>new dependency</project>',
    );
    assert.notEqual(await sourceFingerprint(owned), resource);
  } finally {
    await rm(owned, { recursive: true, force: true });
  }
});

test('le premier lancement crée backend/.env avec le compte de démo, sans jamais le remplacer', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'emploi-go-env-test-'));
  try {
    await writeFile(
      join(directory, '.env.example'),
      'DB_PASSWORD=local\nDEV_AUTH_ACCOUNT=false\n# DEV_AUTH_PASSWORD=\n# CONFIRMATION_TOKEN_KEY=\n',
    );
    assert.equal(await ensureLocalEnv(directory), true);
    const created = await readFile(join(directory, '.env'), 'utf8');
    assert.match(
      created,
      /^DB_PASSWORD=local\nDEV_AUTH_ACCOUNT=true\nDEV_AUTH_PASSWORD=[\w-]{32}\nCONFIRMATION_TOKEN_KEY=[\w+/]{43}=\n$/,
    );
    assert.equal(await ensureLocalEnv(directory), false);
    assert.equal(await readFile(join(directory, '.env'), 'utf8'), created);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
