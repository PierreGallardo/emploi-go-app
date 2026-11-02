import { errorMessage } from './errors.ts';
import type { SignalOptions } from './dev-process.ts';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { acquireLock, lockPath } from './lock.ts';
import {
  CommandError,
  backendDir,
  frontendBuildDir,
  loadBackendEnv,
  maven,
  npm,
  runCommand,
} from './dev-process.ts';

type Step = (options: {
  env: NodeJS.ProcessEnv;
  signal: AbortSignal;
}) => Promise<unknown>;

const npmStep =
  (...args: string[]): Step =>
  ({ env, signal }) =>
    npm(args, { env, signal, capture: true });

// One writer per build folder: other commands wait for their turn.
const locked =
  (scope: string, directory: string, command: string, step: Step): Step =>
  async (options) => {
    const lock = await acquireLock(lockPath(scope, directory), {
      command,
      whenBusy: 'wait',
      signal: options.signal,
    });
    try {
      return await step(options);
    } finally {
      await lock.release();
    }
  };

const mavenStep = (...args: string[]) =>
  locked(
    'backend',
    join(backendDir, 'target'),
    `mvn ${args.at(-1)}`,
    ({ env, signal }) => maven(['-q', ...args], { env, signal, capture: true }),
  );

const scriptTests: Step = ({ env, signal }) =>
  runCommand(process.execPath, ['--test', 'scripts/*.test.ts'], {
    env,
    signal,
    capture: true,
    label: 'node --test',
  });

const contract: Step = async ({ signal }) => {
  const { checkApi } = await import('./api-check.ts');
  return checkApi({ signal });
};

export const checkBlocks: Record<string, Step[]> = {
  scripts: [
    npmStep('exec', '--', 'tsc', '-p', 'tsconfig.scripts.json'),
    scriptTests,
  ],
  format: [npmStep('exec', '--', 'prettier', '--check', '.')],
  frontend: [
    npmStep('run', 'lint', '-w', 'frontend'),
    npmStep('run', 'typecheck', '-w', 'frontend'),
    npmStep('run', 'test', '-w', 'frontend'),
    // The size budget reads the build: it runs under the same lock, before another build replaces it.
    locked('frontend', frontendBuildDir, 'frontend build', async (options) => {
      await npmStep('run', 'build', '-w', 'frontend')(options);
      return npmStep('run', 'size', '-w', 'frontend')(options);
    }),
  ],
  contract: [contract],
  backend: [mavenStep('verify')],
};

export const testBlocks: Record<string, Step[]> = {
  scripts: [scriptTests],
  frontend: [npmStep('run', 'test', '-w', 'frontend')],
  backend: [mavenStep('test')],
};

function duration(milliseconds: number) {
  const seconds = Math.round(milliseconds / 1000);
  return seconds < 60
    ? `${seconds}s`
    : `${Math.floor(seconds / 60)}m${String(seconds % 60).padStart(2, '0')}s`;
}

/** Runs the blocks in parallel; resolves to the names of the failed ones. */
export async function runBlocks(
  blocks: Record<string, Step[]>,
  names: string[],
  {
    signal,
    env = process.env,
    log = console.log,
    now = () => performance.now(),
  }: SignalOptions & {
    env?: NodeJS.ProcessEnv;
    log?: (message: string) => void;
    now?: () => number;
  } = {},
) {
  const unknown = names.filter((name) => !Object.hasOwn(blocks, name));
  if (unknown.length)
    throw new Error(
      `Unknown check: ${unknown.join(', ')}. Expected: ${Object.keys(blocks).join(', ')}.`,
    );
  const controller = new AbortController();
  const abort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  const width = Math.max(...names.map((name) => name.length));
  log(`Running ${names.join(', ')}…`);
  const failed: string[] = [];
  try {
    await Promise.all(
      names.map(async (name) => {
        const start = now();
        try {
          for (const step of blocks[name]!)
            await step({ env, signal: controller.signal });
          log(`✓ ${name.padEnd(width)}  ${duration(now() - start)}`);
        } catch (error) {
          if (controller.signal.aborted) throw error;
          failed.push(name);
          const output =
            error instanceof CommandError && error.output
              ? `${errorMessage(error).split('\n')[0]}\n${error.output}`
              : errorMessage(error);
          log(
            `✗ ${name.padEnd(width)}  ${duration(now() - start)}\n${output.replace(/^/gm, '  ')}`,
          );
        }
      }),
    );
  } finally {
    signal?.removeEventListener('abort', abort);
  }
  return failed;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const controller = new AbortController();
  process.once('SIGINT', () => controller.abort());
  process.once('SIGTERM', () => controller.abort());
  const [first, ...rest] = process.argv.slice(2);
  const blocks = first === 'test' ? testBlocks : checkBlocks;
  const requested = first === 'test' ? rest : process.argv.slice(2);
  try {
    const failed = await runBlocks(
      blocks,
      requested.length ? requested : Object.keys(blocks),
      { signal: controller.signal, env: await loadBackendEnv() },
    );
    if (failed.length) {
      console.error(`\nFailed: ${failed.join(', ')}.`);
      process.exitCode = 1;
    }
  } catch (error) {
    if (!controller.signal.aborted) console.error(errorMessage(error));
    process.exitCode = 1;
  }
}
