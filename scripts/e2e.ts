import { errorMessage } from './errors.ts';
import { randomBytes } from 'node:crypto';
import { startLocalMailbox } from './local-mailbox.ts';
import type { ProcessOptions, SignalOptions } from './dev-process.ts';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startIsolatedBackend } from './api-isolated.ts';
import { freePort, frontendBuildDir, npm } from './dev-process.ts';
import { acquireLock, lockPath } from './lock.ts';
import type { Lock } from './lock.ts';
import { startGoogleFixture } from './google-fixture.ts';

export async function runEndToEnd({
  signal,
  playwrightArgs = [],
}: SignalOptions & { playwrightArgs?: string[] } = {}) {
  signal?.throwIfAborted();
  const port = await freePort();
  const origin = `http://127.0.0.1:${port}`;
  let backend;
  let frontendBuild: Lock | undefined;
  const mailbox = await startLocalMailbox(0);
  let google: Awaited<ReturnType<typeof startGoogleFixture>> | undefined;
  const preparation = new AbortController();
  const stopPreparation = () => preparation.abort(signal?.reason);
  signal?.addEventListener('abort', stopPreparation, { once: true });
  let frontendReady: Promise<unknown> = Promise.resolve();
  try {
    // The frontend build and Chromium do not need the backend: prepare them while it starts.
    // The preview serves frontend/build until the end, so its lock is kept until then.
    frontendReady = (async () => {
      frontendBuild = await acquireLock(
        lockPath('frontend', frontendBuildDir),
        {
          command: 'npm run test:e2e',
          whenBusy: 'wait',
          signal: preparation.signal,
        },
      );
      const quiet: ProcessOptions = {
        signal: preparation.signal,
        capture: true,
      };
      await Promise.all([
        npm(['run', 'build', '-w', 'frontend'], quiet),
        npm(
          [
            'exec',
            '-w',
            'frontend',
            '--',
            'playwright',
            'install',
            'chromium',
            'webkit',
          ],
          quiet,
        ),
      ]);
    })();
    frontendReady.catch(() => {});
    console.log('Starting a temporary backend and building the frontend…');
    google = await startGoogleFixture(
      `${origin}/api/auth/google/callback/google`,
    );
    const password = randomBytes(24).toString('base64url');
    backend = await startIsolatedBackend({
      profile: 'local,contract,test',
      signal,
      env: {
        DEV_AUTH_ACCOUNT: 'true',
        DEV_AUTH_PASSWORD: password,
        REGISTRATION_MAIL_MODE: 'local',
        RESEND_API_KEY: '',
        CONFIRMATION_TOKEN_KEY: randomBytes(32).toString('base64'),
        PUBLIC_APP_ORIGIN: origin,
        LOCAL_MAIL_URL: `${mailbox.url}/emails`,
        EMPLOI_GO_AUTH_QUOTA_SCOPES_REGISTRATION_MAIL_SEND_DELAY: '1',
        GOOGLE_ENABLED: 'true',
        GOOGLE_CLIENT_ID: 'fixture-client',
        GOOGLE_CLIENT_SECRET: 'fixture-secret',
        GOOGLE_REDIRECT_URI: `${origin}/api/auth/google/callback/google`,
        SPRING_APPLICATION_JSON: JSON.stringify({
          'emploi-go': {
            google: {
              'authorization-uri': `${google.url}/authorize`,
              'token-uri': `${google.url}/token`,
              'jwk-set-uri': `${google.url}/jwks`,
              'issuer-uri': google.url,
            },
          },
        }),
      },
    });
    const env = {
      ...process.env,
      API_TARGET: backend.baseUrl,
      E2E_PORT: String(port),
      E2E_AUTH_PASSWORD: password,
      E2E_MAILBOX_URL: mailbox.url,
      E2E_GOOGLE_PROVIDER: google.url,
      E2E_POSTGRES_CONTAINER: backend.postgresContainer,
      // Failure-page ARIA snapshots can reveal password inputs even with trace:off.
      PLAYWRIGHT_NO_COPY_PROMPT: '1',
    };
    const options: ProcessOptions = {
      env,
      signal,
      stdio: ['ignore', 'inherit', 'inherit'],
    };
    await frontendReady;
    await npm(
      ['run', 'test:e2e', '-w', 'frontend', '--', ...playwrightArgs],
      options,
    );
  } finally {
    signal?.removeEventListener('abort', stopPreparation);
    preparation.abort();
    await frontendReady.catch(() => {});
    await frontendBuild?.release();
    const stopped = await Promise.allSettled([
      backend?.stop(),
      mailbox.stop(),
      google?.stop(),
    ]);
    for (const result of stopped)
      if (result.status === 'rejected')
        console.error(errorMessage(result.reason));
    if (stopped.some((result) => result.status === 'rejected'))
      process.exitCode = 1;
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const controller = new AbortController();
  process.once('SIGINT', () => controller.abort());
  process.once('SIGTERM', () => controller.abort());
  try {
    await runEndToEnd({
      signal: controller.signal,
      playwrightArgs: process.argv.slice(2),
    });
  } catch (error) {
    console.error(errorMessage(error));
    process.exitCode = 1;
  }
}
