import assert from 'node:assert/strict';
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  utimes,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  applicationVariables,
  buildIsolatedJar,
  withoutApplicationSettings,
} from './api-isolated.ts';

test('the isolated contract API ignores local application settings', async () => {
  const variables = await applicationVariables();
  for (const name of [
    'REGISTRATION_MAIL_MODE',
    'PUBLIC_APP_ORIGIN',
    'GOOGLE_ENABLED',
    'RESEND_API_KEY',
    'DEV_AUTH_ACCOUNT',
    'DEV_AUTH_PASSWORD',
    'SESSION_TIMEOUT',
  ])
    assert.ok(variables.has(name), name);

  const env = withoutApplicationSettings(
    {
      PATH: '/usr/bin',
      JAVA_HOME: '/opt/java',
      REGISTRATION_MAIL_MODE: 'resend',
      PUBLIC_APP_ORIGIN: 'http://localhost:5173',
      GOOGLE_ENABLED: 'true',
      SPRING_PROFILES_ACTIVE: 'local',
      EMPLOI_GO_DATASET_SYNCHRONIZATION_DOWNLOAD_TIMEOUT: '1m',
    },
    variables,
  );
  assert.deepEqual(env, { PATH: '/usr/bin', JAVA_HOME: '/opt/java' });
});

test('le backend isolé est compilé une fois puis réutilisé tant que ses sources ne changent pas', async () => {
  const root = await mkdtemp(join(tmpdir(), 'emploi-go-isolated-test-'));
  try {
    const sources = join(root, 'backend');
    const directory = join(root, 'target-isolated');
    await mkdir(join(sources, 'src', 'main'), { recursive: true });
    await writeFile(join(sources, 'pom.xml'), '<project/>');
    await writeFile(join(sources, 'src', 'main', 'App.java'), 'class App {}');
    let builds = 0;
    const build = async () => {
      builds++;
      await mkdir(directory, { recursive: true });
      await writeFile(join(directory, 'app.jar'), `build ${builds}`);
    };
    const options = { directory, sources, build };
    const first = await buildIsolatedJar(
      await mkdtemp(join(root, 'run-')),
      options,
    );
    const second = await buildIsolatedJar(
      await mkdtemp(join(root, 'run-')),
      options,
    );
    assert.equal(builds, 1);
    assert.equal(await readFile(second, 'utf8'), 'build 1');
    assert.notEqual(second, first);
    await writeFile(
      join(sources, 'src', 'main', 'App.java'),
      'class App { int x; }',
    );
    const third = await buildIsolatedJar(
      await mkdtemp(join(root, 'run-')),
      options,
    );
    assert.equal(builds, 2);
    assert.equal(await readFile(third, 'utf8'), 'build 2');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('une sauvegarde pendant la compilation isolée relance la compilation', async () => {
  const root = await mkdtemp(join(tmpdir(), 'emploi-go-isolated-test-'));
  try {
    const sources = join(root, 'backend');
    const directory = join(root, 'target-isolated');
    await mkdir(join(sources, 'src', 'main'), { recursive: true });
    await writeFile(join(sources, 'pom.xml'), '<project/>');
    let builds = 0;
    const build = async () => {
      builds++;
      await mkdir(directory, { recursive: true });
      await writeFile(join(directory, 'app.jar'), `build ${builds}`);
      if (builds === 1)
        await writeFile(
          join(sources, 'src', 'main', 'Saved.java'),
          'class Saved {}',
        );
    };
    const jar = await buildIsolatedJar(root, { directory, sources, build });
    assert.equal(builds, 2);
    assert.equal(await readFile(jar, 'utf8'), 'build 2');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('une compilation isolée en échec n’est jamais réutilisée, et des sources instables font abandonner', async () => {
  const root = await mkdtemp(join(tmpdir(), 'emploi-go-isolated-test-'));
  try {
    const sources = join(root, 'backend');
    const directory = join(root, 'target-isolated');
    await mkdir(join(sources, 'src', 'main'), { recursive: true });
    await writeFile(join(sources, 'pom.xml'), '<project/>');
    let builds = 0;
    const failing = async () => {
      builds++;
      await mkdir(directory, { recursive: true });
      await writeFile(join(directory, 'partial.jar'), 'partial');
      throw new Error('compilation failed');
    };
    await assert.rejects(
      buildIsolatedJar(root, { directory, sources, build: failing }),
      /compilation failed/,
    );
    const working = async () => {
      builds++;
      await writeFile(join(directory, 'app.jar'), 'complete');
    };
    const jar = await buildIsolatedJar(root, {
      directory,
      sources,
      build: working,
    });
    assert.equal(builds, 2);
    assert.equal(await readFile(jar, 'utf8'), 'complete');
    let edits = 0;
    // Sources newer than the last build, then edited again during every attempt.
    await writeFile(
      join(sources, 'src', 'main', 'Edited.java'),
      'class Edited {}',
    );
    const unstable = async () => {
      await writeFile(
        join(sources, 'src', 'main', 'Edited.java'),
        `class Edited { int v = ${++edits}; }`,
      );
      await writeFile(join(directory, 'app.jar'), 'mixed');
    };
    await assert.rejects(
      buildIsolatedJar(root, { directory, sources, build: unstable }),
      /kept changing/,
    );
    assert.equal(edits, 3);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('une modification annulée pendant la compilation isolée relance quand même la compilation', async () => {
  const root = await mkdtemp(join(tmpdir(), 'emploi-go-isolated-test-'));
  try {
    const sources = join(root, 'backend');
    const directory = join(root, 'target-isolated');
    const file = join(sources, 'src', 'main', 'App.java');
    await mkdir(join(sources, 'src', 'main'), { recursive: true });
    await writeFile(join(sources, 'pom.xml'), '<project/>');
    await writeFile(file, 'class App {}');
    let builds = 0;
    const build = async () => {
      builds++;
      await mkdir(directory, { recursive: true });
      await writeFile(join(directory, 'app.jar'), `build ${builds}`);
      if (builds === 1) {
        await writeFile(file, 'class App { int edited; }');
        await writeFile(file, 'class App {}');
        await utimes(file, new Date(0), new Date(0));
      }
    };
    await buildIsolatedJar(root, { directory, sources, build });
    assert.equal(builds, 2);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
