import { errorCode, errorMessage } from './errors.ts';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import type { SignalOptions } from './dev-process.ts';
import { apiDocumentUrl, parseSyncArguments } from './dev-options.ts';
import { acquireLock, lockPath } from './lock.ts';

export const rootDir = fileURLToPath(new URL('../', import.meta.url));
export const contractPath = join(rootDir, 'contracts/openapi.json');
export const typesPath = join(rootDir, 'frontend/src/api/schema.d.ts');

export function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [
          key,
          canonicalize((value as Record<string, unknown>)[key]),
        ]),
    );
  }
  return value;
}

export interface ApiArtifacts {
  contract: string;
  types: string;
}
export interface ApiOptions extends SignalOptions {
  url?: string;
  beforePublish?: () => Promise<void>;
}

export async function createArtifacts(
  document: unknown,
): Promise<ApiArtifacts> {
  if (
    document === null ||
    typeof document !== 'object' ||
    !('openapi' in document) ||
    typeof document.openapi !== 'string' ||
    !/^3\.[01]\.\d+$/.test(document.openapi) ||
    !('info' in document) ||
    document.info === null ||
    typeof document.info !== 'object' ||
    !('title' in document.info) ||
    !document.info.title ||
    !('version' in document.info) ||
    !document.info.version
  ) {
    throw new Error('The backend does not provide a valid OpenAPI 3 contract.');
  }
  const [
    { default: openapiTS, astToString, COMMENT_HEADER },
    { format, resolveConfig },
  ] = await Promise.all([import('openapi-typescript'), import('prettier')]);
  const normalized = canonicalize(document);
  const ast = await openapiTS(JSON.stringify(normalized), {
    alphabetize: true,
  });
  const [contractConfig, typesConfig] = await Promise.all([
    resolveConfig(contractPath),
    resolveConfig(typesPath),
  ]);
  return {
    contract: await format(JSON.stringify(normalized), {
      ...contractConfig,
      plugins: [],
      filepath: contractPath,
    }),
    types: await format(COMMENT_HEADER + astToString(ast), {
      ...typesConfig,
      plugins: [],
      filepath: typesPath,
    }),
  };
}

async function readExisting(path: string) {
  try {
    return await readFile(path, 'utf8');
  } catch (error) {
    if (errorCode(error) === 'ENOENT') return null;
    throw error;
  }
}

// Generation must finish successfully before either tracked file is replaced.
async function writeArtifacts(
  artifacts: ApiArtifacts,
  { signal, beforePublish }: ApiOptions = {},
) {
  const entries: [string, string][] = [
    [contractPath, artifacts.contract],
    [typesPath, artifacts.types],
  ];
  const pending: [string, string][] = [];
  try {
    for (const [path, content] of entries) {
      if ((await readExisting(path)) === content) continue;
      await mkdir(dirname(path), { recursive: true });
      const temporary = `${path}.${randomUUID()}.tmp`;
      pending.push([temporary, path]);
      await writeFile(temporary, content, { encoding: 'utf8', signal });
    }
    signal?.throwIfAborted();
    await beforePublish?.();
    for (const [temporary, path] of pending) await rename(temporary, path);
    return pending.length > 0;
  } finally {
    await Promise.all(pending.map(([path]) => rm(path, { force: true })));
  }
}

async function withLock<T>(
  action: () => Promise<T>,
  signal?: AbortSignal,
): Promise<T> {
  const lock = await acquireLock(lockPath('api', rootDir), {
    command: 'API contract generation',
    whenBusy: 'wait',
    signal,
  });
  try {
    return await action();
  } finally {
    await lock.release();
  }
}

export async function fetchArtifacts({
  url = apiDocumentUrl(process.env.API_TARGET),
  signal,
}: ApiOptions = {}) {
  const response = await fetch(url, {
    signal: signal
      ? AbortSignal.any([signal, AbortSignal.timeout(15_000)])
      : AbortSignal.timeout(15_000),
    redirect: 'error',
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw new Error(
      `OpenAPI export refused: HTTP ${response.status} (${url}).`,
    );
  }
  return createArtifacts(await response.json());
}

/** Resolves to true when the contract or the types changed. */
export async function syncApi(options: ApiOptions = {}) {
  return withLock(async () => {
    const artifacts = await fetchArtifacts(options);
    return writeArtifacts(artifacts, options);
  }, options.signal);
}

export async function generateApi() {
  return withLock(async () => {
    const artifacts = await createArtifacts(
      JSON.parse(await readFile(contractPath, 'utf8')),
    );
    return writeArtifacts(artifacts);
  });
}

export async function compareArtifacts(artifacts: ApiArtifacts) {
  const stale = [];
  const entries: [string, string][] = [
    [contractPath, artifacts.contract],
    [typesPath, artifacts.types],
  ];
  for (const [path, content] of entries) {
    if ((await readExisting(path)) !== content) stale.push(path);
  }
  if (stale.length) {
    throw new Error(
      `The contract or the types differ from the current backend:\n${stale.join('\n')}\nRun npm run dev (or npm run api:sync with the API running) and commit the updated files.`,
    );
  }
  console.log('API contract and types match the backend.');
}

async function runCli() {
  const args = process.argv.slice(3);
  if (process.argv.length < 3 || (process.argv[2] !== 'sync' && args.length))
    throw new Error(
      'Usage: node scripts/api.ts sync [--api-port <port>]|generate',
    );
  switch (process.argv[2]) {
    case 'sync':
      console.log(
        'Exporting the contract of the running API (start it from the current branch).',
      );
      console.log(
        (await syncApi({ url: parseSyncArguments(args) }))
          ? 'API contract and types updated.'
          : 'API contract and types already up to date.',
      );
      break;
    case 'generate':
      console.log(
        (await generateApi())
          ? 'API contract and types updated.'
          : 'API contract and types already up to date.',
      );
      break;
    default:
      throw new Error(
        'Usage: node scripts/api.ts sync [--api-port <port>]|generate',
      );
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  runCli().catch((error) => {
    console.error(errorMessage(error));
    process.exitCode = 1;
  });
}
