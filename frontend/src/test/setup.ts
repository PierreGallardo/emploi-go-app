import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { notifyManager } from '@tanstack/react-query';
import { afterAll, afterEach, beforeEach, vi } from 'vitest';
import { closeSessionSignal } from '@/features/auth/session/session-signal';
import { FakeBroadcastChannel, forgetChannels } from './broadcast';
import {
  assertNoUnhandledRequests,
  server,
  takeUnhandledRequests,
} from './server';

// TanStack notifies in a timer by default: a microtask lets `act()` flush the notification before an assertion.
notifyManager.setScheduler((callback) => queueMicrotask(callback));

// jsdom has no matchMedia: a light system, unless a test replaces it.
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
});

// jsdom has no ResizeObserver: nothing is measured, so a component sees the size it is given.
Object.defineProperty(window, 'ResizeObserver', {
  configurable: true,
  writable: true,
  value: class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
});

// jsdom has no Web Locks: model them. As in a browser, a waiting request is rejected as soon as its signal aborts, without waiting for the holder.
let lockTail: Promise<unknown> = Promise.resolve();
Object.defineProperty(navigator, 'locks', {
  configurable: true,
  value: {
    request: (
      _name: string,
      options: { signal?: AbortSignal },
      operation: () => Promise<unknown>,
    ) => {
      const { signal } = options;
      return new Promise((resolve, reject) => {
        if (signal?.aborted) return reject(signal.reason);
        const abandon = () => reject(signal?.reason);
        signal?.addEventListener('abort', abandon, { once: true });
        lockTail = lockTail.then(async () => {
          signal?.removeEventListener('abort', abandon);
          // Abandoned while it waited: already rejected, and it never holds the lock.
          if (signal?.aborted) return;
          try {
            resolve(await operation());
          } catch (error) {
            reject(error);
          }
        });
      });
    },
  },
});

// jsdom has no layout: `input-otp` looks for a password manager badge with `elementFromPoint`, which finds nothing here.
document.elementFromPoint = () => null;

// Node does not resolve relative URLs as a browser does: resolve them against `document.baseURI`, since the client has no base URL.
const NodeRequest = globalThis.Request;
globalThis.Request = class extends NodeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(
      input instanceof NodeRequest
        ? input
        : new URL(String(input), document.baseURI).href,
      init,
    );
  }
};

// The server starts before a test file imports the API client, which keeps the `fetch` it finds at creation.
server.listen({ onUnhandledFrame: 'error' });
afterAll(() => server.close());

// On `http://localhost` cookies have no `__Host-` prefix.
beforeEach(() => {
  // A request that outlived the previous test must not fail this one.
  takeUnhandledRequests();
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel);
  document.cookie = 'XSRF-TOKEN=csrf-fictif; Path=/';
});
afterEach(async () => {
  // Reset the handlers first: a failure below must not leave the answers of this test to the next one.
  server.resetHandlers();
  cleanup();
  // The page-wide channel of the session signal belongs to the test that opened it.
  closeSessionSignal();
  forgetChannels();
  for (const name of ['XSRF-TOKEN', 'EMPLOIGO_RECONNECTABLE'])
    document.cookie = `${name}=; Path=/; Max-Age=0`;
  localStorage.clear();
  sessionStorage.clear();
  delete document.documentElement.dataset.theme;
  await assertNoUnhandledRequests();
});
