import { errorMessage } from './errors.ts';
import type {
  ManagedProcess,
  ProcessOptions,
  SignalOptions,
} from './dev-process.ts';
import { randomBytes, randomUUID } from 'node:crypto';
import {
  copyFile,
  mkdtemp,
  readdir,
  readFile,
  rm,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { resetClasses, sourceFingerprint, sourceStamp } from './dev-build.ts';
import {
  backendDir,
  checkDocker,
  freePort,
  javaCommand,
  maskSecrets,
  maven,
  runCommand,
  spawnManaged,
  stopProcess,
  waitForHealth,
} from './dev-process.ts';
import { acquireLock, lockPath } from './lock.ts';
import { isolatedLabel, removeLeftovers } from './services.ts';

const applicationPrefixes = ['SPRING_', 'EMPLOI_GO_'];
const postgresImage = 'emploi-go/postgres';
export const isolatedDir = join(backendDir, 'target-isolated');

// Includes the commented keys of backend/.env.example.
export async function applicationVariables(source = backendDir) {
  const variables = new Set<string>();
  const resources = join(source, 'src', 'main', 'resources');
  for (const name of await readdir(resources)) {
    if (!/^application.*\.ya?ml$/.test(name)) continue;
    const text = await readFile(join(resources, name), 'utf8');
    for (const match of text.matchAll(/\$\{([A-Z][A-Z0-9_]*)/g))
      variables.add(match[1]!);
  }
  const example = await readFile(join(source, '.env.example'), 'utf8');
  for (const match of example.matchAll(/^#?\s*([A-Z][A-Z0-9_]*)=/gm))
    variables.add(match[1]!);
  return variables;
}

// Like CI: ignores the machine's settings (backend/.env, terminal) and keeps the system environment.
export function withoutApplicationSettings(
  env: NodeJS.ProcessEnv,
  variables: ReadonlySet<string>,
) {
  return Object.fromEntries(
    Object.entries(env).filter(
      ([key]) =>
        key === 'JAVA_HOME' ||
        (!variables.has(key) &&
          !applicationPrefixes.some((prefix) => key.startsWith(prefix))),
    ),
  );
}

async function builtJar(directory: string) {
  const jars = (await readdir(directory).catch(() => [])).filter((name) =>
    name.endsWith('.jar'),
  );
  return jars.length === 1 ? join(directory, jars[0]!) : undefined;
}

/**
 * Builds the current sources once into a folder of its own, untouched by dev and tests, and
 * reuses that JAR while the sources are unchanged. Returns a copy the caller owns.
 */
export async function buildIsolatedJar(
  destination: string,
  {
    env = process.env,
    signal,
    directory = isolatedDir,
    sources = backendDir,
    build = (args: string[]) =>
      maven(args, { env, signal, capture: true, timeoutMs: 600_000 }),
  }: SignalOptions & {
    env?: NodeJS.ProcessEnv;
    directory?: string;
    sources?: string;
    build?: (args: string[]) => Promise<unknown>;
  } = {},
) {
  const lock = await acquireLock(lockPath('backend', directory), {
    command: 'backend build',
    whenBusy: 'wait',
    signal,
  });
  try {
    const marker = join(directory, 'source-fingerprint.txt');
    for (let attempt = 0; attempt < 3; attempt++) {
      const stamp = await sourceStamp(sources);
      const fingerprint = await sourceFingerprint(sources);
      let jar = await builtJar(directory);
      if (
        !jar ||
        (await readFile(marker, 'utf8').catch(() => '')) !== fingerprint
      ) {
        await rm(marker, { force: true });
        await resetClasses(directory);
        for (const name of await readdir(directory).catch(() => []))
          if (name.endsWith('.jar')) await rm(join(directory, name));
        await build([
          '-q',
          '-f',
          join(sources, 'pom.xml'),
          `-Demploi-go.build-directory=${directory}`,
          '-Dmaven.test.skip=true',
          'package',
        ]);
        // A save during the build would mix two versions of the sources.
        if ((await sourceStamp(sources)) !== stamp) continue;
        jar = await builtJar(directory);
        if (!jar) throw new Error('The backend build produced no single JAR.');
        await writeFile(marker, fingerprint);
      }
      const copy = join(destination, basename(jar));
      await copyFile(jar, copy);
      return copy;
    }
    throw new Error(
      'The backend sources kept changing during the build. Try again.',
    );
  } finally {
    await lock.release();
  }
}

export interface IsolatedOptions extends SignalOptions {
  profile?: string;
  env?: NodeJS.ProcessEnv;
}

export async function startIsolatedBackend({
  profile = 'contract',
  env: extraEnv = {},
  signal,
}: IsolatedOptions = {}) {
  const containers: string[] = [];
  let temporary: string | undefined;
  const building = new AbortController();
  let jarPromise: Promise<string> | undefined;
  let apiProcess: ManagedProcess | undefined;
  const env = { ...process.env, ...extraEnv };
  const docker = (args: string[], options: ProcessOptions = {}) =>
    runCommand('docker', args, {
      env,
      capture: true,
      timeoutMs: 180_000,
      signal,
      ...options,
    });
  const stop = async () => {
    await stopProcess(apiProcess);
    for (const name of containers.reverse()) {
      try {
        await docker(['rm', '-f', '-v', name], {
          signal: undefined,
          timeoutMs: 30_000,
        });
      } catch (error) {
        console.error(
          `Could not remove container ${name}: ${errorMessage(error)}`,
        );
      }
    }
    containers.length = 0;
    if (temporary) {
      if (
        dirname(temporary) !== tmpdir() ||
        !basename(temporary).startsWith('emploi-go-isolated-')
      ) {
        throw new Error(`Unexpected temporary folder: ${temporary}`);
      }
      await rm(temporary, { recursive: true, force: true });
      temporary = undefined;
    }
  };
  try {
    await checkDocker({ env, signal });
    await removeLeftovers(env);
    temporary = await mkdtemp(join(tmpdir(), 'emploi-go-isolated-'));
    const suffix = randomUUID();
    const password = randomUUID();
    env.DB_PASSWORD = password;
    env.REDIS_PASSWORD = password;
    const postgres = `emploi-go-isolated-pg-${suffix}`;
    const redis = `emploi-go-isolated-redis-${suffix}`;
    // The containers start while the JAR builds.
    const pendingJar = buildIsolatedJar(temporary, {
      env,
      signal: AbortSignal.any([building.signal, ...(signal ? [signal] : [])]),
    });
    pendingJar.catch(() => {});
    jarPromise = pendingJar;
    // The PostgreSQL of backend/compose.yml, with PostGIS; Docker's cache keeps rebuilds instant.
    await docker(
      ['build', '-q', '-t', postgresImage, join(backendDir, 'postgres')],
      {
        timeoutMs: 600_000,
      },
    );
    containers.push(postgres);
    await docker([
      'run',
      '-d',
      '--name',
      postgres,
      '--label',
      isolatedLabel,
      '--label',
      `fr.emploigo.owner-pid=${process.pid}`,
      // Disposable checks keep real PostgreSQL transactions without host-disk fsync stalls.
      '--tmpfs',
      '/var/lib/postgresql:rw,size=512m',
      '-e',
      'POSTGRES_DB=emploigo',
      '-e',
      'POSTGRES_USER=emploigo',
      '-e',
      `POSTGRES_PASSWORD=${password}`,
      '-p',
      '127.0.0.1::5432',
      '--health-cmd',
      'pg_isready -h 127.0.0.1 -U emploigo -d emploigo',
      '--health-interval',
      '1s',
      '--health-timeout',
      '3s',
      '--health-retries',
      '90',
      postgresImage,
    ]);
    containers.push(redis);
    await docker([
      'run',
      '-d',
      '--name',
      redis,
      '--label',
      isolatedLabel,
      '--label',
      `fr.emploigo.owner-pid=${process.pid}`,
      '-p',
      '127.0.0.1::6379',
      'redis:8.10.2',
      'redis-server',
      '--requirepass',
      password,
    ]);
    const deadline = Date.now() + 120_000;
    let ready = false;
    let lastPg = 'unknown';
    let lastRedis = 'unknown';
    while (Date.now() < deadline) {
      signal?.throwIfAborted();
      const pg = await docker([
        'inspect',
        '--format',
        '{{.State.Health.Status}}',
        postgres,
      ]);
      lastPg = pg.stdout.trim();
      let pong = '';
      try {
        const response = await docker([
          'exec',
          '-e',
          `REDISCLI_AUTH=${password}`,
          redis,
          'redis-cli',
          'ping',
        ]);
        pong = response.stdout.trim();
        lastRedis = pong;
      } catch (error) {
        signal?.throwIfAborted();
        lastRedis = errorMessage(error);
        // Redis may still be binding its port immediately after docker run.
        if (Date.now() >= deadline) throw error;
      }
      if (lastPg === 'healthy' && pong === 'PONG') {
        ready = true;
        break;
      }
      await delay(500, undefined, { signal });
    }
    if (!ready) {
      for (const name of containers) {
        try {
          const logs = await docker(['logs', '--tail', '30', name], {
            timeoutMs: 15_000,
          });
          console.error(
            `${name}\n${(logs.stdout + logs.stderr).replaceAll(password, '[hidden]')}`,
          );
        } catch (error) {
          console.error(`No logs for ${name}: ${errorMessage(error)}`);
        }
      }
      throw new Error(
        `The temporary services are not ready: PostgreSQL ${lastPg}, Redis ${lastRedis.replaceAll(password, '[hidden]')}.`,
      );
    }
    const jar = await pendingJar;
    const mapping = async (name: string, port: number) => {
      const result = await docker(['port', name, `${port}/tcp`]);
      const match = /^127\.0\.0\.1:(\d+)$/.exec(result.stdout.trim());
      if (!match) throw new Error(`Temporary Docker port not found: ${name}.`);
      return match[1]!;
    };
    const pgPort = await mapping(postgres, 5432);
    const redisPort = await mapping(redis, 6379);
    const apiPort = await freePort();
    let managementPort = await freePort();
    while (managementPort === apiPort) managementPort = await freePort();
    const javaEnv = {
      ...withoutApplicationSettings(process.env, await applicationVariables()),
      ...extraEnv,
      SPRING_PROFILES_ACTIVE: profile,
      SERVER_ADDRESS: '127.0.0.1',
      SERVER_PORT: String(apiPort),
      MANAGEMENT_PORT: String(managementPort),
      DB_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/emploigo`,
      DB_USERNAME: 'emploigo',
      DB_PASSWORD: password,
      REDIS_HOST: '127.0.0.1',
      REDIS_PORT: redisPort,
      REDIS_PASSWORD: password,
      QUOTA_KEY: randomBytes(32).toString('base64'),
    };
    apiProcess = spawnManaged(javaCommand(env), ['-jar', jar], {
      cwd: temporary,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: javaEnv,
    });
    // Spring logs stay hidden; their end explains a failed startup.
    let logs = '';
    const keep = (data: Buffer) => {
      logs = (logs + data).slice(-5000);
    };
    apiProcess.stdout?.on('data', keep);
    apiProcess.stderr?.on('data', keep);
    try {
      await waitForHealth(
        `http://127.0.0.1:${managementPort}/actuator/health`,
        {
          child: apiProcess,
          signal,
          timeoutMs: 300_000,
        },
      );
    } catch (error) {
      throw new Error(
        `${errorMessage(error)}${logs ? `\n${maskSecrets(logs, javaEnv)}` : ''}`,
      );
    }
    return {
      baseUrl: `http://127.0.0.1:${apiPort}`,
      managementUrl: `http://127.0.0.1:${managementPort}`,
      postgresContainer: postgres,
      stop,
    };
  } catch (error) {
    building.abort();
    await jarPromise?.catch(() => {});
    await stop();
    throw error;
  }
}
