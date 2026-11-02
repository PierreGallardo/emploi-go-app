import type { Plugin } from 'vite';

const watching = Symbol.for('emploi-go.dev-session-watch');

/** Stops the dev server started by `npm run dev` once that session is gone, however it ended. */
export function devSessionPlugin(): Plugin {
  return {
    name: 'emploi-go-dev-session',
    apply: 'serve',
    configureServer() {
      const session = Number(process.env.EMPLOI_GO_DEV_SESSION_PID);
      // A configuration change reloads this module and reruns the hook: one watch per process.
      const state = globalThis as typeof globalThis & { [watching]?: boolean };
      if (state[watching] || !Number.isInteger(session) || session <= 0) return;
      state[watching] = true;
      setInterval(() => {
        try {
          process.kill(session, 0);
        } catch {
          process.exit(0);
        }
      }, 1000).unref();
    },
  };
}
