/** Constant message between tabs, with no session data; without `BroadcastChannel`, other tabs read at their next check (`sessionStaleTime` at most). */
const channelName = 'emploi-go-session';
const message = 'session-changed';

// One channel per page: a second channel object would receive the announcements of the first and read its own write.
let channel: BroadcastChannel | undefined;
const handlers = new Set<() => void>();

function open() {
  if (channel) return channel;
  if (typeof BroadcastChannel === 'undefined') return undefined;
  try {
    channel = new BroadcastChannel(channelName);
    channel.addEventListener('message', ({ data }) => {
      if (data === message) handlers.forEach((handler) => handler());
    });
  } catch {
    channel = undefined;
  }
  return channel;
}

/** Announces a session change once the write is confirmed, or once its outcome is unknown; a channel failure never fails the write. */
export function announceSessionChange() {
  try {
    open()?.postMessage(message);
  } catch {
    // The other tabs read the session at their next check.
  }
}

export function onSessionChanged(handler: () => void) {
  handlers.add(handler);
  open();
  return () => {
    handlers.delete(handler);
    if (handlers.size === 0) closeSessionSignal();
  };
}

export function closeSessionSignal() {
  channel?.close();
  channel = undefined;
  handlers.clear();
}
