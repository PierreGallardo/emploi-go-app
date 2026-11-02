import type { Hash } from 'node:crypto';
import { createHash, randomUUID } from 'node:crypto';
import {
  cp,
  mkdir,
  readFile,
  readdir,
  rm,
  stat,
  utimes,
  writeFile,
} from 'node:fs/promises';
import { join, relative, resolve, sep } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import {
  backendDir,
  type ManagedProcess,
  type SignalOptions,
} from './dev-process.ts';

export const developmentDir = join(backendDir, 'target-dev');
export const buildDir = join(developmentDir, 'build');
export const runtimeDir = join(developmentDir, 'runtime');
export const triggerName = '.reload-trigger';
export const generationName = 'emploi-go-dev-generation.txt';

async function hashSources(
  directory: string,
  update: (hash: Hash, path: string) => Promise<unknown>,
) {
  const hash = createHash('sha256');
  async function visit(directory: string): Promise<void> {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) await update(hash.update(path), path);
    }
  }
  await update(hash, join(directory, 'pom.xml'));
  await visit(join(directory, 'src', 'main'));
  return hash.digest('hex');
}

export function sourceFingerprint(directory = backendDir) {
  return hashSources(directory, async (hash, path) =>
    hash.update(await readFile(path)),
  );
}

// Unlike the content, it changes when a file is edited then restored during a build.
export function sourceStamp(directory = backendDir) {
  return hashSources(directory, async (hash, path) => {
    const { mtimeMs, size } = await stat(path);
    hash.update(`${mtimeMs}:${size}`);
  });
}

// Maven keeps classes of deleted sources: compile from empty outputs. Runtime output stays untouched.
export async function resetClasses(directory = buildDir) {
  for (const name of ['classes', 'generated-sources', 'maven-status']) {
    const output = resolve(directory, name);
    if (!output.startsWith(resolve(directory) + sep))
      throw new Error('Invalid build output.');
    await rm(output, { recursive: true, force: true });
  }
}

export async function publishGeneration(directory = developmentDir) {
  const source = join(directory, 'build', 'classes');
  const destination = join(directory, 'runtime', 'classes');
  const generation = randomUUID();
  await mkdir(destination, { recursive: true });
  const previousTrigger = await stat(join(destination, triggerName)).catch(
    () => undefined,
  );
  async function removeOutput(path: string) {
    if (!resolve(path).startsWith(resolve(destination) + sep))
      throw new Error('Invalid runtime output.');
    await rm(path, { recursive: true, force: true });
  }
  async function synchronize(from: string, to: string) {
    const entries = await readdir(from, { withFileTypes: true });
    const names = new Set(entries.map((entry) => entry.name));
    for (const entry of await readdir(to, { withFileTypes: true })) {
      if (relative(destination, join(to, entry.name)) === triggerName) continue;
      if (!names.has(entry.name)) await removeOutput(join(to, entry.name));
    }
    for (const entry of entries) {
      const target = join(to, entry.name);
      const existing = await stat(target).catch(() => undefined);
      if (existing && existing.isDirectory() !== entry.isDirectory())
        await removeOutput(target);
      if (entry.isDirectory()) {
        await mkdir(target, { recursive: true });
        await synchronize(join(from, entry.name), target);
      } else await cp(join(from, entry.name), target);
    }
  }
  await synchronize(source, destination);
  await cp(
    join(directory, 'build', 'dev-classpath.txt'),
    join(directory, 'runtime', 'dev-classpath.txt'),
  );
  await writeFile(join(destination, generationName), generation);
  // DevTools compares dates: every trigger needs a strictly later modification time.
  const timestamp = new Date(
    Math.max(Date.now(), (previousTrigger?.mtimeMs ?? 0) + 1000),
  );
  await writeFile(join(destination, triggerName), generation);
  await utimes(join(destination, triggerName), timestamp, timestamp);
  return generation;
}

export function matchesGeneration(
  value: unknown,
  generation: string,
  pid: number | undefined,
) {
  return (
    value !== null &&
    typeof value === 'object' &&
    'generation' in value &&
    value.generation === generation &&
    'pid' in value &&
    value.pid === pid
  );
}

export async function waitForGeneration(
  path: string,
  generation: string,
  child: ManagedProcess,
  {
    signal,
    timeoutMs = 300_000,
    isCurrent,
  }: SignalOptions & {
    timeoutMs?: number;
    isCurrent?: () => Promise<boolean>;
  } = {},
) {
  const deadline = Date.now() + timeoutMs;
  let failure: Error | undefined;
  child.completion?.then(
    () => {
      failure = new Error(
        'The backend stopped before the new version started.',
      );
    },
    (error: unknown) => {
      failure = error instanceof Error ? error : new Error(String(error));
    },
  );
  while (Date.now() < deadline) {
    signal?.throwIfAborted();
    if (isCurrent && !(await isCurrent()))
      throw new Error(
        'Sources changed during startup; compiling the new version.',
      );
    if (failure) throw failure;
    if (child.exitCode !== null || child.signalCode !== null)
      throw new Error('The backend stopped before the new version started.');
    try {
      if (
        matchesGeneration(
          JSON.parse(await readFile(path, 'utf8')),
          generation,
          child.pid,
        )
      )
        return;
    } catch {
      /* Readiness can be absent while the context starts. */
    }
    await delay(300, undefined, { signal });
  }
  throw new Error(`The backend did not start version ${generation}: ${path}`);
}
