import assert from 'node:assert/strict';
import test from 'node:test';
import {
  defaultPorts,
  developmentPlan,
  needsSessionMailbox,
  originSettings,
  parseDevelopmentArguments,
  parseSyncArguments,
  resolvePorts,
} from './dev-options.ts';

const allFree = async () => true;
const noFreePort = async () => assert.fail('no port should be picked');

test('les ports actuels restent les valeurs par défaut', async () => {
  const options = parseDevelopmentArguments([]);
  assert.equal(options.mode, 'default');
  assert.deepEqual(
    await resolvePorts(options, allFree, noFreePort),
    defaultPorts,
  );
  const plan = developmentPlan(options, {});
  assert.deepEqual(plan.services, ['postgres', 'redis']);
  assert.deepEqual(plan.runtimeEnv, {});
  assert.equal(plan.contractUrl, 'http://127.0.0.1:8080/v3/api-docs');
  assert.deepEqual(plan.urls, [
    ['App', 'http://localhost:5173'],
    ['API', 'http://localhost:8080/swagger-ui/index.html'],
  ]);
});

test('un port par défaut occupé passe sur un port libre, un port choisi occupé est refusé', async () => {
  const busy = new Set([5173, 8081]);
  const isFree = async (port: number) => !busy.has(port);
  const free = [8080, 49_001, 49_002];
  const resolved = await resolvePorts(
    parseDevelopmentArguments([]),
    isFree,
    async () => free.shift()!,
  );
  // A port picked for the frontend is not handed out again to the API.
  assert.equal(resolved['frontend-port'], 8080);
  assert.equal(resolved['api-port'], 49_001);
  assert.equal(resolved['api-management-port'], 49_002);
  await assert.rejects(
    resolvePorts(
      parseDevelopmentArguments(['--api-management-port', '8081']),
      async (port) => port !== 8081,
      noFreePort,
    ),
    /Port 8081 is in use \(--api-management-port/,
  );
});

test('les ports personnalisés pilotent Vite, son proxy, les deux JVM, la supervision et le contrat', () => {
  const options = parseDevelopmentArguments([
    'all',
    '--frontend-port',
    '5273',
    '--api-port=8180',
    '--api-management-port',
    '8181',
    '--worker-port',
    '8190',
    '--worker-management-port=8191',
  ]);
  const plan = developmentPlan(options, {
    API_TARGET: 'http://localhost:9999',
    OTHER: 'preserved',
  });
  assert.deepEqual(plan.frontendArgs.slice(-3), ['--', '--port', '5273']);
  assert.equal(plan.frontendEnv.API_TARGET, 'http://127.0.0.1:8180');
  assert.equal(plan.frontendEnv.OTHER, 'preserved');
  assert.deepEqual(
    plan.runtimes.map(({ port, managementPort }) => [port, managementPort]),
    [
      [8180, 8181],
      [8190, 8191],
    ],
  );
  assert.equal(plan.contractUrl, 'http://127.0.0.1:8180/v3/api-docs');
  assert.deepEqual(plan.urls.at(-1), ['Worker', 'http://localhost:8190']);
});

test('frontend seul garde API_TARGET et permet de choisir le port de l’API externe', async () => {
  const env = { API_TARGET: 'http://localhost:9080' };
  const options = parseDevelopmentArguments(['frontend']);
  const plan = developmentPlan(options, env);
  assert.equal(plan.frontendEnv.API_TARGET, env.API_TARGET);
  assert.deepEqual(plan.services, []);
  assert.deepEqual(plan.runtimes, []);
  const checked: number[] = [];
  await resolvePorts(
    parseDevelopmentArguments(['frontend', '--api-port', '9180']),
    async (port) => (checked.push(port), true),
    noFreePort,
  );
  assert.deepEqual(checked, [5173]);
  const explicit = developmentPlan(
    parseDevelopmentArguments(['frontend', '--api-port', '9180']),
    env,
  );
  assert.equal(explicit.frontendEnv.API_TARGET, 'http://127.0.0.1:9180');
});

test('les liens des emails et le retour Google suivent un frontend déplacé, sauf réglage personnalisé', () => {
  assert.deepEqual(originSettings({}, 'http://localhost:5173'), {});
  assert.deepEqual(
    originSettings(
      {
        PUBLIC_APP_ORIGIN: 'http://localhost:5173',
        GOOGLE_REDIRECT_URI:
          'http://localhost:5173/api/auth/google/callback/google',
      },
      'http://localhost:5174',
    ),
    {
      PUBLIC_APP_ORIGIN: 'http://localhost:5174',
      GOOGLE_REDIRECT_URI:
        'http://localhost:5174/api/auth/google/callback/google',
    },
  );
  assert.deepEqual(
    originSettings(
      { PUBLIC_APP_ORIGIN: 'https://demo.example.org' },
      'http://localhost:5174',
    ),
    {},
  );
});

test('frontend refuse un proxy vers son propre port local après résolution de la cible', () => {
  assert.throws(
    () =>
      developmentPlan(
        parseDevelopmentArguments(['frontend', '--frontend-port', '8080']),
        {},
      ),
    /API proxy targets the frontend port/,
  );
  for (const host of ['localhost', '127.0.0.1', '[::1]']) {
    assert.throws(
      () =>
        developmentPlan(
          parseDevelopmentArguments(['frontend', '--frontend-port', '5273']),
          {
            API_TARGET: `http://${host}:5273`,
          },
        ),
      /API proxy targets the frontend port/,
    );
  }
  const remote = developmentPlan(
    parseDevelopmentArguments(['frontend', '--frontend-port', '5273']),
    {
      API_TARGET: 'http://api.example.com:5273',
    },
  );
  assert.equal(remote.frontendEnv.API_TARGET, 'http://api.example.com:5273');
});

test('ports invalides, répétés, dupliqués et options sans composant sont refusés', () => {
  for (const value of ['0', '65536', '-1', '1.5', 'NaN', '', '8080=bad']) {
    assert.throws(
      () => parseDevelopmentArguments([`--api-port=${value}`]),
      /expects a port/,
    );
  }
  assert.throws(
    () => parseDevelopmentArguments(['--api-port', '8081']),
    /used twice/,
  );
  assert.throws(
    () => parseDevelopmentArguments(['all', '--worker-port', '8080']),
    /used twice/,
  );
  assert.throws(
    () =>
      parseDevelopmentArguments([
        'frontend',
        '--frontend-port',
        '8080',
        '--api-port',
        '8080',
      ]),
    /used twice/,
  );
  assert.throws(
    () => parseDevelopmentArguments(['--api-port', '8180', '--api-port=8280']),
    /given twice/,
  );
  assert.throws(
    () => parseDevelopmentArguments(['--worker-port', '8190']),
    /does not apply/,
  );
  assert.throws(() => parseDevelopmentArguments(['api']), /Expected dev mode/);
  assert.throws(
    () => parseDevelopmentArguments(['--unknown', '42']),
    /Unknown/,
  );
});

test('api:sync utilise API_TARGET ou le port explicite sans toucher les contrôles isolés', () => {
  assert.equal(parseSyncArguments([], {}), 'http://127.0.0.1:8080/v3/api-docs');
  assert.equal(
    parseSyncArguments([], { API_TARGET: 'http://localhost:9080' }),
    'http://localhost:9080/v3/api-docs',
  );
  assert.equal(
    parseSyncArguments(['--api-port', '9180'], {
      API_TARGET: 'http://localhost:9080',
    }),
    'http://127.0.0.1:9180/v3/api-docs',
  );
  assert.equal(
    parseSyncArguments(['--api-port=9180'], {}),
    'http://127.0.0.1:9180/v3/api-docs',
  );
  assert.throws(
    () => parseSyncArguments(['--api-port=9180=bad']),
    /expects a port/,
  );
  assert.throws(
    () => parseSyncArguments([], { API_TARGET: 'file:///tmp/api' }),
    /HTTP/,
  );
  assert.throws(
    () =>
      parseSyncArguments([], { API_TARGET: 'http://user:secret@localhost' }),
    /without credentials/,
  );
  assert.throws(() => parseSyncArguments(['--wrong', '9080']), /Usage/);
});

test('la session lance la boîte email seulement en mode local, sans adresse personnalisée', () => {
  assert.equal(needsSessionMailbox({}), false);
  assert.equal(
    needsSessionMailbox({ REGISTRATION_MAIL_MODE: 'resend' }),
    false,
  );
  assert.equal(needsSessionMailbox({ REGISTRATION_MAIL_MODE: 'local' }), true);
  assert.equal(
    needsSessionMailbox({
      REGISTRATION_MAIL_MODE: 'local',
      LOCAL_MAIL_URL: 'http://127.0.0.1:8025/emails',
    }),
    true,
  );
  assert.equal(
    needsSessionMailbox({
      REGISTRATION_MAIL_MODE: 'local',
      LOCAL_MAIL_URL: 'http://127.0.0.1:9025/emails',
    }),
    false,
  );
});
