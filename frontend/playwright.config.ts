import { defineConfig, devices } from '@playwright/test';

const port = process.env.E2E_PORT ?? '4173';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  workers: 2,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // A test that passes only on a retry is reported as flaky, and the run fails.
  failOnFlakyTests: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  // Traces, screenshots and videos can hold passwords and cookies: off unless a spec turns them on for its own purpose.
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // The WebKit subset: only the journeys tagged @webkit, where the engine changes behaviour.
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      grep: /@webkit/,
    },
  ],
  webServer: {
    command: `npm run preview -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
  },
});
