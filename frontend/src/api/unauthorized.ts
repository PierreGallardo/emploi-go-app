/**
 * A failure of `reconnect` is thrown and ends the read as an error, not as a request to sign in.
 */
export type Reconnection = {
  account: () => string | undefined;
  reconnect: () => Promise<string | undefined>;
};

let installed: Reconnection | undefined;

type Attempt = {
  running: boolean;
  failed: boolean;
  end: number;
  outcome: Promise<string | undefined>;
};
let epoch = 0;
let attempt: Attempt | undefined;

const sent = new WeakMap<
  Request,
  { epoch: number; account: string | undefined }
>();

/**
 * `providers.tsx` installs it, since the client cannot import `features/auth`, and only one may be installed at a time.
 */
export function onUnauthorized(reconnection: Reconnection): () => void {
  if (installed)
    throw new Error('La reconnexion automatique est déjà installée.');
  installed = reconnection;
  return () => {
    if (installed !== reconnection) return;
    installed = undefined;
    attempt = undefined;
  };
}

/** Notes, as the request leaves, the account it is sent for: a 401 is answered for that account, not another. */
export function noteSender(request: Request) {
  sent.set(request, { epoch, account: installed?.account() });
}

/** Reads are the only requests sent again: a write is never replayed automatically. */
function isProtectedRead({ method, url }: Request) {
  const target = new URL(url);
  return (
    (method === 'GET' || method === 'HEAD') &&
    target.origin === globalThis.location.origin &&
    // The session read has its own reconnection, and the other routes of `/api/auth` answer for themselves.
    !target.pathname.startsWith('/api/auth/')
  );
}

/**
 * A 401 joins the running attempt, or the failure of one that ended after its read left, so the API sees one attempt per episode.
 */
function reconnectionFor(reconnection: Reconnection, sentAt: number) {
  if (attempt && (attempt.running || (attempt.failed && sentAt < attempt.end)))
    return attempt.outcome;
  const current: Attempt = {
    running: true,
    failed: false,
    end: Infinity,
    outcome: reconnection.reconnect().then(
      (account) => {
        current.running = false;
        current.end = ++epoch;
        return account;
      },
      (failure: unknown) => {
        current.running = false;
        current.failed = true;
        current.end = ++epoch;
        throw failure;
      },
    ),
  };
  attempt = current;
  return current.outcome;
}

/**
 * The reconnection is shared, so one read abandoning ends only its own wait.
 */
function untilAbort<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abandon = () => reject(signal.reason);
    signal.addEventListener('abort', abandon, { once: true });
    promise
      .finally(() => signal.removeEventListener('abort', abandon))
      .then(resolve, reject);
  });
}

/**
 * `send` skips the client's middleware, so a second 401 is returned as it is; the clone keeps the read's signal.
 */
export async function replayAfterReconnection({
  request,
  response,
  send,
}: {
  request: Request;
  response: Response;
  send: (request: Request) => Promise<Response>;
}): Promise<Response | undefined> {
  const reconnection = installed;
  const origin = sent.get(request);
  if (
    response.status !== 401 ||
    !reconnection ||
    origin?.account === undefined ||
    // Another account is signed in since the read left: its answer is of no use to the screen that asked.
    origin.account !== reconnection.account() ||
    !isProtectedRead(request)
  )
    return undefined;
  if (request.signal.aborted) throw request.signal.reason;
  const account = await untilAbort(
    reconnectionFor(reconnection, origin.epoch),
    request.signal,
  );
  if (account !== origin.account) return undefined;
  // The answer is replaced: nobody reads its body any more.
  void response.body?.cancel().catch(() => undefined);
  return send(request.clone());
}
