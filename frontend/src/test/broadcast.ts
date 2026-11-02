/** jsdom has no BroadcastChannel, and Node's would keep the test process alive; delivery is a microtask and never reaches the sender. */
const open = new Set<FakeBroadcastChannel>();

export class FakeBroadcastChannel extends EventTarget {
  constructor(readonly name: string) {
    super();
    open.add(this);
  }
  postMessage(data: unknown) {
    if (!open.has(this)) throw new DOMException('Closed', 'InvalidStateError');
    for (const peer of open)
      if (peer !== this && peer.name === this.name)
        queueMicrotask(() => {
          if (open.has(peer))
            peer.dispatchEvent(new MessageEvent('message', { data }));
        });
  }
  close() {
    open.delete(this);
  }
}

export const openChannels = () => open.size;
export const forgetChannels = () => open.clear();

export function otherTab() {
  const channel = new FakeBroadcastChannel('emploi-go-session');
  const received: unknown[] = [];
  channel.addEventListener('message', (event) =>
    received.push((event as MessageEvent).data),
  );
  return { channel, received };
}
