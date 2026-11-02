import { errorCode } from './errors.ts';
import { isProcessAlive } from './dev-process.ts';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, unlinkSync } from 'node:fs';
import {
  mkdir,
  open,
  readFile,
  stat,
  unlink,
  utimes,
  writeFile,
} from 'node:fs/promises';
import { tmpdir, userInfo } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

export interface LockOptions {
  /** Shown to the commands that find this lock busy. */
  command: string;
  /** `replace` asks the owner to stop, `wait` waits until it finishes. */
  whenBusy: 'replace' | 'wait';
  onStopRequest?: () => void;
  signal?: AbortSignal;
  heartbeatMs?: number;
  /** A lock file without a readable owner is abandoned once unchanged this long. */
  staleMs?: number;
  /** A live owner PID is trusted unless its file stays unchanged this long: the PID may be reused. */
  reusedPidMs?: number;
  replaceTimeoutMs?: number;
  now?: () => number;
  pause?: (ms: number, signal?: AbortSignal) => Promise<unknown>;
  log?: (message: string) => void;
}

export interface Lock {
  release(): Promise<void>;
  /** For a forced exit, where no asynchronous cleanup can run. */
  releaseSync(): void;
}

interface Owner {
  pid?: number;
  token?: string;
  command?: string;
}

export function lockPath(scope: string, directory: string) {
  const absolute = resolve(directory);
  const key = createHash('sha256')
    .update(process.platform === 'win32' ? absolute.toLowerCase() : absolute)
    .digest('hex')
    .slice(0, 32);
  const user = process.getuid?.() ?? userInfo().username;
  return join(tmpdir(), `emploi-go-locks-${user}`, `${scope}-${key}.lock`);
}

// Linux shares /tmp between users: only the owner may read a token or request a stop.
async function ensurePrivateDirectory(directory: string) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  if (process.platform === 'win32') return;
  const { uid, mode } = await stat(directory);
  if (uid !== process.getuid?.() || (mode & 0o077) !== 0)
    throw new Error(
      `Unsafe lock folder ${directory}: it must be yours and closed to other users.`,
    );
}

function parseOwner(content: string | undefined): Owner | undefined {
  try {
    const owner: unknown = JSON.parse(content ?? '');
    return owner !== null && typeof owner === 'object' ? owner : undefined;
  } catch {
    return undefined;
  }
}

async function readOptional(path: string) {
  try {
    return await readFile(path, 'utf8');
  } catch (error) {
    if (errorCode(error) === 'ENOENT') return undefined;
    throw error;
  }
}

function describe(owner: Owner | undefined) {
  return `${owner?.command ?? 'another command'}${owner?.pid ? ` (PID ${owner.pid})` : ''}`;
}

export async function acquireLock(
  path: string,
  {
    command,
    whenBusy,
    onStopRequest,
    signal,
    heartbeatMs = 2000,
    staleMs = 10_000,
    reusedPidMs = 600_000,
    replaceTimeoutMs = 60_000,
    now = () => performance.now(),
    pause = (ms, abortSignal) => delay(ms, undefined, { signal: abortSignal }),
    log = console.log,
  }: LockOptions,
): Promise<Lock> {
  const token = randomUUID();
  const stopPath = `${path}.stop`;
  await ensurePrivateDirectory(dirname(path));
  let announced = false;
  let target: string | undefined;
  let replaceDeadline = 0;
  let observed:
    { content: string; modified: number; since: number } | undefined;
  for (;;) {
    signal?.throwIfAborted();
    try {
      const handle = await open(path, 'wx', 0o600);
      try {
        await handle.writeFile(
          JSON.stringify({ pid: process.pid, token, command }),
        );
      } catch (error) {
        await handle.close();
        await unlink(path);
        throw error;
      }
      await handle.close();
      break;
    } catch (error) {
      if (errorCode(error) !== 'EEXIST') throw error;
    }
    const content = await readOptional(path);
    const modified = await stat(path).then(
      (stats) => stats.mtimeMs,
      (error: unknown) => {
        if (errorCode(error) !== 'ENOENT') throw error;
        return undefined;
      },
    );
    if (content === undefined || modified === undefined) continue;
    const owner = parseOwner(content);
    if (observed?.content !== content || observed.modified !== modified)
      observed = { content, modified, since: now() };
    const frozenFor = now() - observed.since;
    // A suspended owner (Ctrl+Z) still has a child writing to the folder: only its death frees it.
    if (
      typeof owner?.pid === 'number'
        ? !isProcessAlive(owner.pid) || frozenFor > reusedPidMs
        : frozenFor > staleMs
    ) {
      if ((await readOptional(path)) === content)
        await unlink(path).catch((error: unknown) => {
          if (errorCode(error) !== 'ENOENT') throw error;
        });
      continue;
    }
    if (
      whenBusy === 'replace' &&
      typeof owner?.token === 'string' &&
      /^[0-9a-f-]{36}$/.test(owner.token)
    ) {
      if (owner.token !== target) {
        log(`Stopping the previous ${describe(owner)}…`);
        await writeFile(stopPath, owner.token, { mode: 0o600 });
        target = owner.token;
        replaceDeadline = now() + replaceTimeoutMs;
      } else if (now() > replaceDeadline) {
        throw new Error(
          `The previous ${describe(owner)} did not stop. Close it, then try again.`,
        );
      }
    } else if (!announced) {
      log(`Waiting for ${describe(owner)} to finish…`);
      announced = true;
    }
    await pause(250, signal);
  }
  let released = false;
  let heartbeat: NodeJS.Timeout | undefined;
  const beat = async () => {
    const time = new Date();
    await utimes(path, time, time).catch(() => {});
    if (onStopRequest && (await readOptional(stopPath)) === token) {
      await unlink(stopPath).catch(() => {});
      onStopRequest();
    }
  };
  // The next beat waits for the previous one: overlapping beats would all read the same stop request.
  const scheduleBeat = () => {
    heartbeat = setTimeout(() => {
      void beat()
        .catch(() => {})
        .finally(() => {
          if (!released) scheduleBeat();
        });
    }, heartbeatMs);
    heartbeat.unref();
  };
  scheduleBeat();
  const owned = (content: string | undefined) =>
    parseOwner(content)?.token === token;
  return {
    async release() {
      if (released) return;
      released = true;
      clearTimeout(heartbeat);
      if (owned(await readOptional(path))) await unlink(path);
    },
    releaseSync() {
      if (released) return;
      released = true;
      clearTimeout(heartbeat);
      try {
        if (owned(readFileSync(path, 'utf8'))) unlinkSync(path);
      } catch (error) {
        if (errorCode(error) !== 'ENOENT') throw error;
      }
    },
  };
}
