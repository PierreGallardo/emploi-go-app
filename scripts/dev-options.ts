import type { ServiceName } from './services.ts';

export type DevMode = 'default' | 'all' | 'frontend';

export const defaultPorts = {
  'frontend-port': 5173,
  'api-port': 8080,
  'api-management-port': 8081,
  'worker-port': 8090,
  'worker-management-port': 8091,
};
export type DevPorts = typeof defaultPorts;
type PortOption = keyof DevPorts;

export function parsePort(value: string, option: string) {
  const port = Number(value);
  if (
    !/^\d+$/.test(value) ||
    !Number.isInteger(port) ||
    port < 1 ||
    port > 65535
  ) {
    throw new Error(`${option} expects a port between 1 and 65535.`);
  }
  return port;
}

export function components(mode: DevMode) {
  return {
    frontend: true,
    api: mode !== 'frontend',
    worker: mode === 'all',
  };
}

// The ports this session listens on; in frontend mode, --api-port only targets an existing API.
function ownedPorts(mode: DevMode): PortOption[] {
  const { api, worker } = components(mode);
  return [
    'frontend-port',
    ...(api ? (['api-port', 'api-management-port'] as const) : []),
    ...(worker ? (['worker-port', 'worker-management-port'] as const) : []),
  ];
}

export function parseDevelopmentArguments(args: string[]) {
  const remaining = [...args];
  const candidate = remaining[0]?.startsWith('--')
    ? 'default'
    : (remaining.shift() ?? 'default');
  if (!['default', 'all', 'frontend'].includes(candidate)) {
    throw new Error('Expected dev mode: default, all or frontend.');
  }
  const mode = candidate as DevMode;
  const ports = { ...defaultPorts };
  const selected = new Set<PortOption>();
  const allowed = new Set<PortOption>([...ownedPorts(mode), 'api-port']);
  while (remaining.length) {
    const argument = remaining.shift()!;
    const separator = argument.indexOf('=');
    const flag = separator < 0 ? argument : argument.slice(0, separator);
    const inline = separator < 0 ? undefined : argument.slice(separator + 1);
    const name = flag.slice(2) as PortOption;
    if (!flag.startsWith('--') || !Object.hasOwn(defaultPorts, name))
      throw new Error(`Unknown dev option: ${argument}`);
    if (!allowed.has(name))
      throw new Error(`--${name} does not apply to this dev mode.`);
    if (selected.has(name)) throw new Error(`--${name} is given twice.`);
    const value = inline ?? remaining.shift() ?? '';
    ports[name] = parsePort(value, `--${name}`);
    selected.add(name);
  }
  const owners = new Map<number, string>();
  for (const name of [
    ...ownedPorts(mode),
    ...(mode === 'frontend' && selected.has('api-port')
      ? (['api-port'] as const)
      : []),
  ]) {
    const previous = owners.get(ports[name]);
    if (previous)
      throw new Error(
        `Port ${ports[name]} is used twice: --${previous} and --${name}.`,
      );
    owners.set(ports[name], name);
  }
  return { mode, ports, selected };
}

type DevelopmentOptions = ReturnType<typeof parseDevelopmentArguments>;

// Default ports stay stable; a busy default (another copy of the repository) moves to a free port.
export async function resolvePorts(
  { mode, ports, selected }: DevelopmentOptions,
  isFree: (port: number) => Promise<boolean>,
  freePort: () => Promise<number>,
) {
  const resolved = { ...ports };
  const taken = new Set<number>();
  for (const name of ownedPorts(mode)) {
    if (taken.has(resolved[name]) || !(await isFree(resolved[name]))) {
      if (selected.has(name))
        throw new Error(
          `Port ${resolved[name]} is in use (--${name} to choose another).`,
        );
      do resolved[name] = await freePort();
      while (taken.has(resolved[name]));
    }
    taken.add(resolved[name]);
  }
  return resolved;
}

export function developmentPlan(
  options: DevelopmentOptions,
  env: NodeJS.ProcessEnv,
) {
  const { mode, ports, selected } = options;
  const { api, worker } = components(mode);
  const apiTarget =
    api || selected.has('api-port')
      ? `http://127.0.0.1:${ports['api-port']}`
      : (env.API_TARGET ?? 'http://127.0.0.1:8080');
  const contractUrl = apiDocumentUrl(apiTarget);
  const target = new URL(contractUrl);
  const targetPort = Number(
    target.port || (target.protocol === 'https:' ? 443 : 80),
  );
  if (
    ['localhost', '127.0.0.1', '[::1]'].includes(target.hostname) &&
    targetPort === ports['frontend-port']
  ) {
    throw new Error(
      'The API proxy targets the frontend port. Choose another --frontend-port, --api-port or API_TARGET.',
    );
  }
  const runtimes = [
    ...(api
      ? [
          {
            label: 'API',
            profiles: 'local',
            port: ports['api-port'],
            managementPort: ports['api-management-port'],
          },
        ]
      : []),
    ...(worker
      ? [
          {
            label: 'Worker',
            profiles: 'local,worker',
            port: ports['worker-port'],
            managementPort: ports['worker-management-port'],
          },
        ]
      : []),
  ];
  const appUrl = `http://localhost:${ports['frontend-port']}`;
  return {
    api,
    worker,
    services: (api ? ['postgres', 'redis'] : []) as ServiceName[],
    runtimes,
    runtimeEnv: originSettings(env, appUrl),
    frontendArgs: [
      'run',
      'dev',
      '-w',
      'frontend',
      '--',
      '--port',
      String(ports['frontend-port']),
    ],
    frontendEnv: { ...env, API_TARGET: apiTarget } as NodeJS.ProcessEnv,
    contractUrl,
    urls: [
      ['App', appUrl],
      ...(api
        ? [
            [
              'API',
              `http://localhost:${ports['api-port']}/swagger-ui/index.html`,
            ],
          ]
        : []),
      ...(worker
        ? [['Worker', `http://localhost:${ports['worker-port']}`]]
        : []),
    ] as [string, string][],
  };
}

const defaultMailUrl = 'http://127.0.0.1:8025/emails';

// In local mail mode the session runs the mailbox, unless the API sends to another address.
export function needsSessionMailbox(env: NodeJS.ProcessEnv) {
  return (
    env.REGISTRATION_MAIL_MODE === 'local' &&
    (env.LOCAL_MAIL_URL ?? defaultMailUrl) === defaultMailUrl
  );
}

const defaultOrigin = 'http://localhost:5173';

// Email links and the Google callback follow the frontend when it moves to another port.
export function originSettings(env: NodeJS.ProcessEnv, appUrl: string) {
  if (
    appUrl === defaultOrigin ||
    (env.PUBLIC_APP_ORIGIN ?? defaultOrigin) !== defaultOrigin
  )
    return {};
  return {
    PUBLIC_APP_ORIGIN: appUrl,
    ...(env.GOOGLE_REDIRECT_URI?.startsWith(`${defaultOrigin}/`)
      ? {
          GOOGLE_REDIRECT_URI:
            appUrl + env.GOOGLE_REDIRECT_URI.slice(defaultOrigin.length),
        }
      : {}),
  };
}

export function apiDocumentUrl(target = 'http://127.0.0.1:8080') {
  let url: URL;
  try {
    url = new URL('/v3/api-docs', target);
  } catch {
    throw new Error('API_TARGET must be a valid HTTP or HTTPS URL.');
  }
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password
  ) {
    throw new Error(
      'API_TARGET must be an HTTP or HTTPS URL without credentials.',
    );
  }
  return url.href;
}

export function parseSyncArguments(
  args: string[],
  env: NodeJS.ProcessEnv = process.env,
) {
  if (!args.length) return apiDocumentUrl(env.API_TARGET);
  const separator = args[0]!.indexOf('=');
  const flag = separator < 0 ? args[0]! : args[0]!.slice(0, separator);
  const inline = separator < 0 ? undefined : args[0]!.slice(separator + 1);
  if (flag !== '--api-port' || args.length !== (inline === undefined ? 2 : 1)) {
    throw new Error('Usage: npm run api:sync -- --api-port <port>');
  }
  return apiDocumentUrl(
    `http://127.0.0.1:${parsePort(inline ?? args[1] ?? '', '--api-port')}`,
  );
}
