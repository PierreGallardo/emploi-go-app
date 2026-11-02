export type Deadline = {
  signal: AbortSignal;
  /** The deadline passed, as opposed to the caller giving up. */
  readonly expired: boolean;
  release: () => void;
};

/** Not `AbortSignal.timeout` with `AbortSignal.any`: `any` is missing in Safari 16.4 to 17.3, and `timeout` raises an `AbortError` in Chrome 103 to 123 that looks like a cancellation, so `expired` tells the two apart. */
export function startDeadline(
  milliseconds: number,
  caller?: AbortSignal,
): Deadline {
  const controller = new AbortController();
  let expired = false;
  const timer = setTimeout(() => {
    if (controller.signal.aborted) return;
    expired = true;
    controller.abort(new DOMException('Délai dépassé.', 'TimeoutError'));
  }, milliseconds);
  const relay = () => controller.abort(caller?.reason);
  if (caller?.aborted) relay();
  else caller?.addEventListener('abort', relay, { once: true });
  return {
    signal: controller.signal,
    get expired() {
      return expired;
    },
    release: () => {
      clearTimeout(timer);
      caller?.removeEventListener('abort', relay);
    },
  };
}

export async function withDeadline<T>(
  milliseconds: number,
  caller: AbortSignal | undefined,
  run: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const deadline = startDeadline(milliseconds, caller);
  try {
    return await run(deadline.signal);
  } finally {
    deadline.release();
  }
}
