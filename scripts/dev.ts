import { errorCode, errorMessage } from './errors.ts';
import type { ManagedProcess, SignalOptions } from './dev-process.ts';
import { EventEmitter, once } from 'node:events';
import { watch } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { acquireLock, lockPath } from './lock.ts';
import type { Lock } from './lock.ts';
import {
  developmentPlan,
  needsSessionMailbox,
  parseDevelopmentArguments,
  resolvePorts,
} from './dev-options.ts';
import {
  developmentDir,
  buildDir,
  runtimeDir,
  triggerName,
  publishGeneration,
  resetClasses,
  sourceFingerprint,
  waitForGeneration,
} from './dev-build.ts';
import {
  CommandError,
  backendDir,
  checkDocker,
  developmentClasspath,
  ensureLocalEnv,
  freePort,
  isPortFree,
  javaCommand,
  loadBackendEnv,
  maven,
  runCommand,
  spawnManaged,
  spawnNpm,
  stopProcess,
  waitForHealth,
} from './dev-process.ts';
import { startLocalMailbox } from './local-mailbox.ts';
import { startServices } from './services.ts';

const windows = process.platform === 'win32';
let forceStop: (() => void) | undefined;

function seconds(start: number) {
  return `${((performance.now() - start) / 1000).toFixed(1)}s`;
}

// Keeps the compiler messages of a Maven failure, without Maven's own advice.
export function buildErrors(error: unknown) {
  const output = error instanceof CommandError ? error.output : '';
  const lines = output
    .split(/\r?\n/)
    .filter((line) => line.startsWith('[ERROR]'))
    .map((line) => line.replace(/^\[ERROR\] ?/, ''));
  const end = lines.findIndex((line) => line.startsWith('-> [Help'));
  const failure = lines.findLastIndex(
    (line, index) =>
      (end < 0 || index < end) && line.startsWith('Failed to execute goal'),
  );
  const details = (
    failure < 0
      ? lines.slice(0, end < 0 ? undefined : end)
      : lines.slice(failure + 1, end < 0 ? undefined : end)
  )
    .filter((line) => line.trim())
    .map((line) => line.replaceAll(`${backendDir}/`, 'backend/'));
  return details.length ? details.join('\n') : errorMessage(error);
}

export async function runDevelopment(
  options: ReturnType<typeof parseDevelopmentArguments>,
  { signal }: SignalOptions = {},
) {
  const started = performance.now();
  const backend = options.mode !== 'frontend';
  if (backend && (await ensureLocalEnv()))
    console.log(
      'Created backend/.env with the demo account jeune_demo (its password is DEV_AUTH_PASSWORD in that file).',
    );
  const env = await loadBackendEnv();
  // Checks the options now; the ports are chosen once a replaced session has released them.
  let plan = developmentPlan(options, env);
  const controller = new AbortController();
  const stop = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', stop, { once: true });
  if (signal?.aborted) stop();
  const sessionSignal = controller.signal;
  const children = new Set<ManagedProcess>();
  const stopping = new Set<ManagedProcess>();
  let runtimes: ManagedProcess[] = [];
  let runtimeFailed = false;
  let ready = false;
  let lock: Lock | undefined;
  let mailbox: Awaited<ReturnType<typeof startLocalMailbox>> | undefined;

  let replaced = false;
  const stopChildren = () => {
    const current = [...children];
    current.forEach((child) => stopping.add(child));
    return Promise.allSettled(current.map(stopProcess));
  };
  // Stops at once, even while the main loop still awaits a file operation.
  sessionSignal.addEventListener('abort', () => void stopChildren(), {
    once: true,
  });
  forceStop = () => {
    for (const child of children) {
      try {
        if (child.pid)
          process.kill(windows ? child.pid : -child.pid, 'SIGKILL');
      } catch {
        // Already stopped.
      }
    }
    lock?.releaseSync();
  };

  function track(child: ManagedProcess, label: string, recoverable = false) {
    children.add(child);
    if (sessionSignal.aborted) {
      stopping.add(child);
      void stopProcess(child).catch(() => {});
    }
    const failed = (reason: string) => {
      if (stopping.has(child) || sessionSignal.aborted) return;
      if (!recoverable)
        return controller.abort(new Error(`${label} stopped (${reason}).`));
      runtimeFailed = true;
      ready = false;
      console.error(
        `${label} stopped (${reason}). Fix the error and save to retry.`,
      );
      changes.emit('change');
    };
    child.completion.then(
      ({ code, signal: childSignal }) => {
        children.delete(child);
        failed(String(childSignal ?? code));
      },
      (error) => {
        children.delete(child);
        failed(errorMessage(error));
      },
    );
    return child;
  }
  async function stopRuntimes() {
    const previous = runtimes;
    runtimes = [];
    ready = false;
    previous.forEach((child) => stopping.add(child));
    await Promise.all(previous.map(stopProcess));
    previous.forEach((child) => stopping.delete(child));
  }
  async function currentFingerprint() {
    try {
      return await sourceFingerprint();
    } catch (error) {
      if (errorCode(error) !== 'ENOENT') throw error;
      return undefined;
    }
  }
  function printReady() {
    const width = Math.max(...plan.urls.map(([label]) => label.length));
    console.log(
      [
        `\nReady in ${seconds(started)}`,
        ...plan.urls.map(([label, url]) => `  ${label.padEnd(width)}  ${url}`),
        '',
      ].join('\n'),
    );
  }
  async function monitorHealth() {
    const healthy = new Map<string, boolean>();
    while (!sessionSignal.aborted) {
      await delay(5000, undefined, { signal: sessionSignal });
      if (!ready) continue;
      for (const { label, managementPort } of plan.runtimes) {
        let up = false;
        try {
          const response = await fetch(
            `http://127.0.0.1:${managementPort}/actuator/health`,
            {
              signal: AbortSignal.any([
                sessionSignal,
                AbortSignal.timeout(2000),
              ]),
            },
          );
          const body: unknown = await response.json();
          up =
            body !== null &&
            typeof body === 'object' &&
            'status' in body &&
            body.status === 'UP';
        } catch {
          if (sessionSignal.aborted) return;
        }
        if (!ready || healthy.get(label) === up) continue;
        if (healthy.has(label) || !up)
          console.log(
            up
              ? `${label} is healthy again.`
              : `${label} is unhealthy: check that Docker is running.`,
          );
        healthy.set(label, up);
      }
    }
  }

  const changes = new EventEmitter();
  let changeCount = 0;
  const changed = () => {
    changeCount++;
    changes.emit('change');
  };
  async function nextChange() {
    await once(changes, 'change', { signal: sessionSignal });
    // Editors save in several steps: let the files settle.
    await delay(300, undefined, { signal: sessionSignal });
  }

  try {
    if (backend) {
      lock = await acquireLock(lockPath('backend', developmentDir), {
        command: 'dev session',
        whenBusy: 'replace',
        onStopRequest: () => {
          replaced = true;
          controller.abort();
        },
        signal: sessionSignal,
      });
      await checkDocker({ env, signal: sessionSignal });
      const version = await runCommand(javaCommand(env), ['-version'], {
        env,
        capture: true,
        signal: sessionSignal,
      });
      if (
        !/(?:version|openjdk) "?25[.\s"]/.test(version.stderr + version.stdout)
      ) {
        throw new Error(
          'Java 25 is required. Check JAVA_HOME and java -version.',
        );
      }
    }
    plan = developmentPlan(
      {
        ...options,
        ports: await resolvePorts(options, isPortFree, freePort),
      },
      env,
    );
    console.log(
      backend ? 'Starting PostgreSQL and Redis…' : 'Starting the frontend…',
    );
    const services = await startServices(env, plan.services, {
      signal: sessionSignal,
    });
    const sessionEnv = { EMPLOI_GO_DEV_SESSION_PID: String(process.pid) };
    sessionSignal.throwIfAborted();
    track(
      spawnNpm(plan.frontendArgs, {
        env: { ...plan.frontendEnv, ...sessionEnv },
      }),
      'Frontend',
    );
    if (!backend) {
      printReady();
      await once(changes, 'never', { signal: sessionSignal });
      return;
    }
    for (const watcher of [
      watch(
        join(backendDir, 'src', 'main'),
        {
          recursive: true,
          signal: sessionSignal,
        },
        changed,
      ),
      watch(backendDir, { signal: sessionSignal }, (_, name) => {
        if (name === 'pom.xml') changed();
      }),
    ])
      watcher.on('error', (error) => controller.abort(error));
    void monitorHealth().catch(() => {});
    // The session owns the local mailbox, so the API always gets its actual address.
    if (needsSessionMailbox(env)) {
      mailbox = await startLocalMailbox(8025).catch((error: unknown) => {
        if (errorCode(error) !== 'EADDRINUSE') throw error;
        return startLocalMailbox(0);
      });
      plan.urls.push(['Mail', mailbox.url]);
    }

    const runtimeEnv = {
      // Framework startup details drown the application logs; backend/.env can bring them back.
      SPRING_MAIN_BANNER_MODE: 'off',
      LOGGING_LEVEL_ROOT: 'warn',
      LOGGING_LEVEL_FR_EMPLOIGO: 'info',
      LOGGING_LEVEL_ORG_FLYWAYDB: 'info',
      ...env,
      ...services.env,
      ...plan.runtimeEnv,
      ...(mailbox ? { LOCAL_MAIL_URL: `${mailbox.url}/emails` } : {}),
    };
    let compiledFingerprint: string | undefined;
    let compiledPom: string | undefined;
    let firstBuild = true;
    while (!sessionSignal.aborted) {
      if (runtimeFailed) {
        await stopRuntimes();
        runtimeFailed = false;
      }
      const seen = changeCount;
      const fingerprint = await currentFingerprint();
      if (!fingerprint || fingerprint === compiledFingerprint) {
        if (changeCount === seen) await nextChange();
        continue;
      }
      // Any save restarts the build: an edit undone meanwhile still left other sources compiled.
      const isCurrent = async () => changeCount === seen;
      const reloadStarted = performance.now();
      ready = false;
      const pom = await readFile(join(backendDir, 'pom.xml'), 'utf8');
      const dependenciesChanged = pom !== compiledPom;
      if (firstBuild) console.log('Compiling the backend…');
      try {
        await resetClasses();
        await maven(
          [
            '-q',
            `-Demploi-go.build-directory=${buildDir}`,
            ...(dependenciesChanged ? ['clean'] : []),
            'compile',
            ...(dependenciesChanged
              ? [
                  'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath',
                  '-Dmdep.includeScope=runtime',
                  `-Dmdep.outputFile=${join(buildDir, 'dev-classpath.txt')}`,
                ]
              : []),
          ],
          { env, signal: sessionSignal, capture: true },
        );
      } catch (error) {
        if (sessionSignal.aborted) throw error;
        await stopRuntimes();
        console.error(
          `\nBackend build failed. Fix the error and save to retry.\n${buildErrors(error)}\n`,
        );
        compiledFingerprint = fingerprint;
        continue;
      }
      if (!(await isCurrent())) continue;
      if (dependenciesChanged) await stopRuntimes();
      const generation = await publishGeneration();
      compiledPom = pom;
      const classpath = await developmentClasspath(runtimeDir);
      let runtimeIndex = 0;
      try {
        for (const { label, profiles, port, managementPort } of plan.runtimes) {
          const readyFile = join(
            developmentDir,
            `ready-${label.toLowerCase()}.json`,
          );
          const existing = runtimes[runtimeIndex++];
          sessionSignal.throwIfAborted();
          const child =
            existing ??
            track(
              spawnManaged(
                javaCommand(env),
                [
                  '-Dspring.devtools.restart.enabled=true',
                  '-cp',
                  classpath,
                  'fr.emploigo.EmploiGoApplication',
                  `--spring.profiles.active=${profiles}`,
                  `--server.port=${port}`,
                  `--management.server.port=${managementPort}`,
                  `--spring.devtools.restart.trigger-file=${triggerName}`,
                  '--spring.devtools.restart.poll-interval=1s',
                  '--spring.devtools.restart.quiet-period=400ms',
                  '--spring.devtools.livereload.enabled=false',
                  `--emploigo.dev.ready-file=${readyFile}`,
                  `--emploigo.dev.session-pid=${process.pid}`,
                ],
                { cwd: backendDir, env: runtimeEnv },
              ),
              label,
              true,
            );
          if (!existing) runtimes.push(child);
          await waitForGeneration(readyFile, generation, child, {
            signal: sessionSignal,
            isCurrent,
          });
          await waitForHealth(
            `http://127.0.0.1:${managementPort}/actuator/health`,
            { child, signal: sessionSignal, isCurrent },
          );
        }
      } catch (error) {
        if (sessionSignal.aborted) throw error;
        if (!(await isCurrent())) continue;
        await stopRuntimes();
        compiledFingerprint = fingerprint;
        console.error(
          `\nBackend failed to start. Fix the error and save to retry.\n${errorMessage(error)}\n`,
        );
        continue;
      }
      // A save during compilation or startup must be compiled before exporting its contract.
      if (!(await isCurrent())) continue;
      if (plan.api) {
        try {
          const { syncApi } = await import('./api.ts');
          const updated = await syncApi({
            url: plan.contractUrl,
            signal: sessionSignal,
            beforePublish: async () => {
              if (
                runtimeFailed ||
                runtimes.some(
                  (child) =>
                    child.exitCode !== null || child.signalCode !== null,
                )
              ) {
                throw new Error('A backend process stopped during the export.');
              }
              if (!(await isCurrent())) {
                throw new Error('Sources changed during the export.');
              }
            },
          });
          if (updated)
            console.log(
              'API contract updated: contracts/openapi.json and frontend/src/api/schema.d.ts.',
            );
        } catch (error) {
          if (sessionSignal.aborted) throw error;
          console.error(
            `API contract not updated: ${errorMessage(error)} The previous files are kept; run npm run api:sync once fixed.`,
          );
        }
      }
      if (runtimeFailed || !(await isCurrent())) continue;
      compiledFingerprint = fingerprint;
      ready = true;
      if (firstBuild) printReady();
      else console.log(`Backend reloaded in ${seconds(reloadStarted)}.`);
      firstBuild = false;
    }
  } catch (error) {
    if (!sessionSignal.aborted) throw error;
    if (
      sessionSignal.reason instanceof Error &&
      sessionSignal.reason.name !== 'AbortError'
    )
      throw sessionSignal.reason;
  } finally {
    signal?.removeEventListener('abort', stop);
    controller.abort();
    await stopChildren();
    try {
      await mailbox?.stop();
    } finally {
      await lock?.release();
    }
    forceStop = undefined;
    console.log(
      replaced
        ? 'Stopped: a new dev session took over.'
        : backend
          ? 'Stopped. PostgreSQL and Redis keep running: npm run services:down stops them.'
          : 'Stopped.',
    );
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const controller = new AbortController();
  let firstSignal: number | undefined;
  const interrupt = () => {
    if (firstSignal === undefined) {
      firstSignal = performance.now();
      console.log('\nStopping… (press Ctrl+C again to force)');
      controller.abort();
      // npm forwards the terminal's Ctrl+C: the same key press arrives twice within milliseconds.
    } else if (performance.now() - firstSignal > 500) {
      forceStop?.();
      process.exit(130);
    }
  };
  for (const name of ['SIGINT', 'SIGTERM', 'SIGHUP'] as const)
    process.on(name, interrupt);
  // A closed terminal makes writes fail; cleanup must still run.
  process.stdout.on('error', () => {});
  process.stderr.on('error', () => {});
  try {
    await runDevelopment(parseDevelopmentArguments(process.argv.slice(2)), {
      signal: controller.signal,
    });
  } catch (error) {
    console.error(errorMessage(error));
    process.exitCode = 1;
  }
}
