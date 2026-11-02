import { errorCode } from './errors.ts';
import type { ChildProcess, SpawnOptions } from 'node:child_process';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import type { AddressInfo } from 'node:net';
import { createServer } from 'node:net';
import { delimiter, dirname, join } from 'node:path';
import { parseEnv } from 'node:util';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export const rootDir = fileURLToPath(new URL('../', import.meta.url));
export const backendDir = join(rootDir, 'backend');
export const frontendBuildDir = join(rootDir, 'frontend', 'build');
export type ManagedProcess = ChildProcess & {
  completion: Promise<{ code: number | null; signal: NodeJS.Signals | null }>;
};
export interface ProcessOptions {
  label?: string;
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  stdio?: SpawnOptions['stdio'];
  capture?: boolean;
  signal?: AbortSignal;
  timeoutMs?: number;
}
export interface SignalOptions {
  signal?: AbortSignal;
}

const windows = process.platform === 'win32';

export async function loadBackendEnv() {
  let local: NodeJS.ProcessEnv = {};
  try {
    local = parseEnv(await readFile(join(backendDir, '.env'), 'utf8'));
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') throw error;
  }
  return { ...local, ...process.env };
}

// First run: a ready-to-use local configuration with the demo account.
export async function ensureLocalEnv(directory = backendDir) {
  const example = await readFile(join(directory, '.env.example'), 'utf8');
  const content = example
    .replace(/^DEV_AUTH_ACCOUNT=.*$/m, 'DEV_AUTH_ACCOUNT=true')
    .replace(
      /^# DEV_AUTH_PASSWORD=.*$/m,
      `DEV_AUTH_PASSWORD=${randomBytes(24).toString('base64url')}`,
    )
    // Ready for REGISTRATION_MAIL_MODE=local: confirmation links need a stable key.
    .replace(
      /^# CONFIRMATION_TOKEN_KEY=.*$/m,
      `CONFIRMATION_TOKEN_KEY=${randomBytes(32).toString('base64')}`,
    );
  try {
    await writeFile(join(directory, '.env'), content, {
      flag: 'wx',
      mode: 0o600,
    });
    return true;
  } catch (error) {
    if (errorCode(error) === 'EEXIST') return false;
    throw error;
  }
}

export async function checkDocker(options: ProcessOptions = {}) {
  try {
    await runCommand('docker', ['info', '--format', '{{.ServerVersion}}'], {
      ...options,
      capture: true,
      timeoutMs: 20_000,
    });
  } catch (error) {
    options.signal?.throwIfAborted();
    throw new Error(
      errorCode(error) === 'ENOENT'
        ? 'Docker is not installed. Install Docker Desktop, then try again.'
        : 'Docker is not running. Start Docker Desktop, then try again.',
    );
  }
}

export function isProcessAlive(pid: number) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    // EPERM: the process exists but belongs to another user.
    return errorCode(error) === 'EPERM';
  }
}

export function javaCommand(env: NodeJS.ProcessEnv = process.env) {
  return env.JAVA_HOME
    ? join(env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java')
    : 'java';
}

export function spawnManaged(
  command: string,
  args: string[] = [],
  options: ProcessOptions = {},
): ManagedProcess {
  const { cwd = rootDir, env = process.env, stdio = 'inherit' } = options;
  let executable = command;
  let parameters = args;
  const isBatch = windows && /\.(cmd|bat)$/i.test(command);
  if (isBatch) {
    // The wrapper and arguments are repository-controlled, never user shell input.
    const quote = (value: string) => `"${value.replaceAll('"', '""')}"`;
    executable = process.env.ComSpec ?? 'cmd.exe';
    parameters = [
      '/d',
      '/s',
      '/c',
      `"${[command, ...args].map(quote).join(' ')}"`,
    ];
  }
  const child = spawn(executable, parameters, {
    cwd,
    env,
    stdio,
    detached: !windows,
    windowsHide: true,
    windowsVerbatimArguments: isBatch,
  });
  const managed = child as ManagedProcess;
  managed.completion = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (code, signal) => resolve({ code, signal }));
  });
  // Every caller can await completion; avoid an unhandled rejection during readiness polling.
  managed.completion.catch(() => {});
  return managed;
}

export class CommandError extends Error {
  readonly output: string;
  constructor(message: string, output: string) {
    super(message);
    this.output = output;
  }
}

export function maskSecrets(text: string, env: NodeJS.ProcessEnv) {
  let masked = text;
  for (const [name, value] of Object.entries(env)) {
    if (/PASSWORD|SECRET|TOKEN|KEY/i.test(name) && value && value.length >= 4)
      masked = masked.replaceAll(value, '[hidden]');
  }
  return masked;
}

export async function runCommand(
  command: string,
  args: string[] = [],
  options: ProcessOptions = {},
) {
  options.signal?.throwIfAborted();
  const child = spawnManaged(command, args, {
    ...options,
    ...(options.capture ? { stdio: ['ignore', 'pipe', 'pipe'] } : {}),
  });
  let stdout = '';
  let stderr = '';
  child.stdout?.on('data', (data) => {
    stdout += data;
  });
  child.stderr?.on('data', (data) => {
    stderr += data;
  });
  let rejectStop: (reason?: unknown) => void = () => {};
  const stopFailure = new Promise<never>((_, reject) => {
    rejectStop = reject;
  });
  stopFailure.catch(() => {});
  const abort = () => void stopProcess(child).catch(rejectStop);
  const timer = options.timeoutMs
    ? setTimeout(abort, options.timeoutMs)
    : undefined;
  timer?.unref();
  options.signal?.addEventListener('abort', abort, { once: true });
  if (options.signal?.aborted) abort();
  try {
    const { code, signal } = await Promise.race([
      child.completion,
      stopFailure,
    ]);
    if (code !== 0) {
      const env = options.env ?? process.env;
      const output = maskSecrets(`${stdout}${stderr}`.trim(), env);
      const details = maskSecrets(
        (stderr.trim() || stdout.trim()).slice(-3000),
        env,
      );
      throw new CommandError(
        `${options.label ?? command} failed (${signal ?? code}).${details ? `\n${details}` : ''}`,
        output,
      );
    }
    return { stdout, stderr };
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', abort);
  }
}

export function maven(args: string[], options: ProcessOptions = {}) {
  return runCommand(
    join(backendDir, windows ? 'mvnw.cmd' : 'mvnw'),
    ['-B', ...args],
    {
      cwd: backendDir,
      label: 'backend',
      ...options,
    },
  );
}

export function npm(args: string[], options: ProcessOptions = {}) {
  const invocation = npmInvocation(args);
  return runCommand(invocation.command, invocation.args, options);
}

export function spawnNpm(args: string[], options: ProcessOptions = {}) {
  const invocation = npmInvocation(args);
  return spawnManaged(invocation.command, invocation.args, options);
}

function npmInvocation(args: string[]) {
  const adjacent = join(
    dirname(process.execPath),
    'node_modules',
    'npm',
    'bin',
    'npm-cli.js',
  );
  const cli =
    process.env.npm_execpath ?? (existsSync(adjacent) ? adjacent : undefined);
  if (cli) return { command: process.execPath, args: [cli, ...args] };
  // Resolve batch wrappers so %~dp0 refers to npm's directory, rather than the cwd.
  const batch = windows
    ? (process.env.PATH ?? '')
        .split(delimiter)
        .map((directory) => join(directory, 'npm.cmd'))
        .find(existsSync)
    : undefined;
  return { command: batch ?? (windows ? 'npm.cmd' : 'npm'), args };
}

export async function stopProcess(child: ManagedProcess | undefined) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null)
    return;
  if (windows) {
    // taskkill /t also stops the children of batch and npm launchers.
    await new Promise<void>((resolve, reject) => {
      const killer = spawn(
        'taskkill.exe',
        ['/pid', String(child.pid), '/t', '/f'],
        {
          stdio: 'ignore',
          windowsHide: true,
        },
      );
      const timer = setTimeout(() => {
        killer.kill();
        reject(
          new Error(
            `Stopping process tree ${child.pid} took more than 35 seconds.`,
          ),
        );
      }, 35_000);
      timer.unref();
      killer.once('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
      killer.once('close', (code) => {
        clearTimeout(timer);
        if (code === 0 || child.exitCode !== null || child.signalCode !== null)
          resolve();
        else
          reject(
            new Error(
              `Could not stop process tree ${child.pid} (taskkill ${code}).`,
            ),
          );
      });
    });
  } else {
    try {
      process.kill(-child.pid, 'SIGTERM');
    } catch (error) {
      if (errorCode(error) !== 'ESRCH') throw error;
    }
    const stopped = await Promise.race([
      child.completion.then(() => true),
      delay(35_000, undefined, { ref: false }).then(() => false),
    ]);
    if (!stopped) {
      try {
        process.kill(-child.pid, 'SIGKILL');
      } catch (error) {
        if (errorCode(error) !== 'ESRCH') throw error;
      }
    }
  }
  await Promise.race([
    child.completion,
    delay(10_000, undefined, { ref: false }).then(() => {
      throw new Error(`Process ${child.pid} did not exit after being stopped.`);
    }),
  ]);
}

function listenOnce(port: number, host: string) {
  return new Promise<number | undefined>((resolve, reject) => {
    const server = createServer();
    server.once('error', (error) =>
      // A machine without IPv6 has no ::1 to share.
      resolve(
        ['EADDRNOTAVAIL', 'EAFNOSUPPORT'].includes(String(errorCode(error)))
          ? port
          : undefined,
      ),
    );
    server.listen(port, host, () => {
      const { port: bound } = server.address() as AddressInfo;
      server.close((error) => (error ? reject(error) : resolve(bound)));
    });
  });
}

// Vite listens on ::1 for localhost, the JVMs on 127.0.0.1: a port is free only if both are.
export async function isPortFree(port: number) {
  return (
    (await listenOnce(port, '127.0.0.1')) !== undefined &&
    (await listenOnce(port, '::1')) !== undefined
  );
}

export async function freePort() {
  for (;;) {
    const port = await listenOnce(0, '127.0.0.1');
    if (port && (await listenOnce(port, '::1')) !== undefined) return port;
  }
}

export async function waitForHealth(
  url: string,
  {
    child,
    signal,
    timeoutMs = 300_000,
    isCurrent,
  }: SignalOptions & {
    child?: ManagedProcess;
    timeoutMs?: number;
    isCurrent?: () => Promise<boolean>;
  } = {},
) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    signal?.throwIfAborted();
    if (isCurrent && !(await isCurrent()))
      throw new Error('Sources changed during the health check.');
    if (child && (child.exitCode !== null || child.signalCode !== null)) {
      throw new Error(`The process stopped before it was ready: ${url}`);
    }
    try {
      const response = await fetch(url, {
        signal: AbortSignal.any([
          AbortSignal.timeout(2000),
          ...(signal ? [signal] : []),
        ]),
      });
      const body: unknown = await response.json();
      if (
        response.ok &&
        body !== null &&
        typeof body === 'object' &&
        'status' in body &&
        body.status === 'UP'
      )
        return;
    } catch (error) {
      if (signal?.aborted) throw error;
    }
    await delay(300, undefined, { signal });
  }
  throw new Error(
    `The backend was not ready after ${timeoutMs / 1000} seconds: ${url}`,
  );
}

export async function developmentClasspath(
  directory = join(backendDir, 'target'),
) {
  return `${join(directory, 'classes')}${delimiter}${(await readFile(join(directory, 'dev-classpath.txt'), 'utf8')).trim()}`;
}
