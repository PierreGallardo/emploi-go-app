import { setupServer } from 'msw/node';
import { handlers } from './handlers';

export const server = setupServer(...handlers);

const unhandled: string[] = [];
// A request is undecided from its start until its match or refusal; MSW reports a match only once the answer is built, so a held answer decides its request itself.
const undecided = new Set<string>();
server.events.on('request:start', ({ requestId }) => {
  undecided.add(requestId);
});
server.events.on('request:match', ({ requestId }) => {
  undecided.delete(requestId);
});
// Our own message names the method and the path only: a query string can carry a name or a username.
server.events.on('request:unhandled', ({ request, requestId }) => {
  undecided.delete(requestId);
  unhandled.push(`${request.method} ${new URL(request.url).pathname}`);
});

/** Called by a held answer (`holdResponse`), since MSW reports its match only at release. */
export function markDecided(requestId: string) {
  undecided.delete(requestId);
}

export function takeUnhandledRequests(): string[] {
  return unhandled.splice(0);
}

// Captured at load, before a test installs fake timers.
const nextMacrotask = globalThis.setImmediate;

// The steps before the server sees a request vary with the previous test, so the wait below runs at least this many turns.
const minimumTurns = 10;

/** Call it before asserting that nothing was sent: an unawaited request may not have started yet; it counts macrotask turns, not time. */
export async function settled() {
  let turns = 0;
  do {
    await new Promise<void>((resolve) => nextMacrotask(resolve));
    turns += 1;
  } while (turns < minimumTurns || (undecided.size > 0 && turns < 50));
}

/** Fails the test that sent a request without a defined answer, even when the screen caught the error. */
export async function assertNoUnhandledRequests() {
  await settled();
  const missed = takeUnhandledRequests();
  if (missed.length > 0)
    throw new Error(
      `Requête sans réponse prévue : ${missed.join(', ')}. Ajouter une réponse dans src/test/handlers ou server.use() dans le test.`,
    );
}
