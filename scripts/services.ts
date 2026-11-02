import { errorMessage } from './errors.ts';
import type { SignalOptions } from './dev-process.ts';
import { existsSync } from 'node:fs';
import { basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  backendDir,
  checkDocker,
  freePort,
  isPortFree,
  isProcessAlive,
  loadBackendEnv,
  rootDir,
  runCommand,
} from './dev-process.ts';

const mainProject = 'emploi-go-local';

// The main checkout keeps its existing containers and data; each worktree gets its own.
export function composeProject(checkout: string, isWorktree: boolean) {
  if (!isWorktree) return mainProject;
  const name = basename(checkout)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
  return `emploi-go-${name || 'worktree'}`;
}

async function currentProject(env: NodeJS.ProcessEnv) {
  try {
    const { stdout } = await runCommand(
      'git',
      ['rev-parse', '--git-dir', '--git-common-dir'],
      { cwd: rootDir, env, capture: true },
    );
    const [gitDir, commonDir] = stdout.trim().split('\n');
    return composeProject(
      rootDir,
      resolve(rootDir, gitDir ?? '') !== resolve(rootDir, commonDir ?? ''),
    );
  } catch {
    return mainProject;
  }
}

const services = {
  postgres: { variable: 'DB_PORT', containerPort: 5432 },
  redis: { variable: 'REDIS_PORT', containerPort: 6379 },
} as const;
export type ServiceName = keyof typeof services;

// Keeps the port of a running container, else the default port if free, else a free one.
export async function choosePort(
  explicit: string | undefined,
  running: number | undefined,
  defaultPort: number,
  variable: string,
  isFree: (port: number) => Promise<boolean>,
  pickFree: () => Promise<number>,
) {
  if (explicit) {
    const port = Number(explicit);
    if (port !== running && !(await isFree(port)))
      throw new Error(`Port ${port} is in use (${variable} in backend/.env).`);
    return port;
  }
  if (running) return running;
  return (await isFree(defaultPort)) ? defaultPort : pickFree();
}

function compose(
  project: string,
  args: string[],
  env: NodeJS.ProcessEnv,
  options: SignalOptions & { capture?: boolean; timeoutMs?: number } = {},
) {
  return runCommand('docker', ['compose', '-p', project, ...args], {
    cwd: backendDir,
    env,
    label: 'docker compose',
    capture: true,
    ...options,
  });
}

async function runningPort(
  project: string,
  service: ServiceName,
  env: NodeJS.ProcessEnv,
) {
  try {
    const { stdout } = await compose(
      project,
      ['port', service, String(services[service].containerPort)],
      env,
    );
    const port = Number(/:(\d+)\s*$/.exec(stdout.trim())?.[1]);
    return Number.isInteger(port) && port > 0 ? port : undefined;
  } catch {
    return undefined;
  }
}

export const isolatedLabel = 'fr.emploigo.purpose=contract-check';

// Compose projects of this repository whose worktree no longer exists.
export function deletedCopies(projects: string[], checkouts: string[]) {
  const live = new Set(
    checkouts.map((checkout, index) => composeProject(checkout, index > 0)),
  );
  return [...new Set(projects)].filter(
    (project) => project.startsWith('emploi-go-') && !live.has(project),
  );
}

async function output(command: string, args: string[], env: NodeJS.ProcessEnv) {
  const { stdout } = await runCommand(command, args, {
    cwd: rootDir,
    env,
    capture: true,
  });
  return stdout.split('\n').filter(Boolean);
}

// Deleting a worktree or killing a check runs no cleanup: the next start removes what they left.
export async function removeLeftovers(env: NodeJS.ProcessEnv) {
  const isolated = await output(
    'docker',
    [
      'ps',
      '-a',
      '--filter',
      `label=${isolatedLabel}`,
      '--format',
      '{{.Names}} {{.Label "fr.emploigo.owner-pid"}}',
    ],
    env,
  );
  const abandoned = isolated
    .map((line) => line.split(' '))
    .filter(([, pid]) => !pid || !isProcessAlive(Number(pid)))
    .map(([name]) => name!);
  if (abandoned.length) {
    await runCommand('docker', ['rm', '-f', '-v', ...abandoned], {
      env,
      capture: true,
    });
    console.log(
      `Removed ${abandoned.length} containers left by an interrupted check.`,
    );
  }
  let checkouts: string[];
  try {
    checkouts = (await output('git', ['worktree', 'list', '--porcelain'], env))
      .filter((line) => line.startsWith('worktree '))
      .map((line) => line.slice('worktree '.length));
  } catch {
    return;
  }
  // The main checkout comes first; a deleted worktree may still be listed until git prunes it.
  const existing = checkouts.filter(
    (checkout, index) => index === 0 || existsSync(checkout),
  );
  const projects = [
    ...(await output(
      'docker',
      ['ps', '-a', '--format', '{{.Label "com.docker.compose.project"}}'],
      env,
    )),
    ...(await output(
      'docker',
      ['volume', 'ls', '--format', '{{.Label "com.docker.compose.project"}}'],
      env,
    )),
  ];
  for (const project of deletedCopies(projects, existing)) {
    await compose(project, ['down', '-v', '--remove-orphans'], env);
    console.log(`Removed the database of a deleted copy (${project}).`);
  }
}

// Returns the variables the API and the worker need to reach the started services.
export async function startServices(
  env: NodeJS.ProcessEnv,
  names: ServiceName[],
  { signal }: SignalOptions = {},
) {
  if (!names.length) return { ports: {}, env: {} };
  await removeLeftovers(env);
  const project = await currentProject(env);
  const ports: Partial<Record<ServiceName, number>> = {};
  for (const name of names) {
    const { variable, containerPort } = services[name];
    ports[name] = await choosePort(
      // A DB_URL of its own pins PostgreSQL: moving it would leave that URL on another database.
      env[variable] ?? (name === 'postgres' && env.DB_URL ? '5432' : undefined),
      await runningPort(project, name, env),
      containerPort,
      variable,
      isPortFree,
      freePort,
    );
  }
  const composeEnv = {
    ...env,
    ...(ports.postgres ? { DB_PORT: String(ports.postgres) } : {}),
    ...(ports.redis ? { REDIS_PORT: String(ports.redis) } : {}),
  };
  await compose(
    project,
    ['up', '-d', '--wait', '--wait-timeout', '120', ...names],
    composeEnv,
    {
      signal,
      timeoutMs: 600_000,
    },
  );
  return {
    ports,
    env: {
      ...(ports.postgres && !env.DB_URL
        ? { DB_URL: `jdbc:postgresql://localhost:${ports.postgres}/emploigo` }
        : {}),
      ...(ports.redis ? { REDIS_PORT: String(ports.redis) } : {}),
    },
  };
}

async function runCli(action: string | undefined) {
  const env = await loadBackendEnv();
  await checkDocker({ env });
  if (action === 'up') {
    console.log('Starting PostgreSQL and Redis…');
    const { ports } = await startServices(env, ['postgres', 'redis']);
    console.log(
      `PostgreSQL (port ${ports.postgres}) and Redis (port ${ports.redis}) are running.`,
    );
  } else if (action === 'down') {
    await compose(await currentProject(env), ['down'], env);
    console.log('PostgreSQL and Redis stopped. Their data is kept.');
  } else if (action === 'reset') {
    await compose(await currentProject(env), ['down', '-v'], env);
    console.log('PostgreSQL and Redis stopped. Their data is deleted.');
  } else {
    throw new Error('Usage: node scripts/services.ts up|down|reset');
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    await runCli(process.argv.length === 3 ? process.argv[2] : undefined);
  } catch (error) {
    console.error(errorMessage(error));
    process.exitCode = 1;
  }
}
