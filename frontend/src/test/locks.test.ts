import { expect, it, onTestFinished } from 'vitest';

const request = <T>(operation: () => Promise<T>, signal?: AbortSignal) =>
  navigator.locks.request('emploi-go-test', { signal }, operation);

function holder() {
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const running = request(() => held);
  onTestFinished(async () => {
    release();
    await running;
  });
  return { release, running };
}

it('rejette une demande en attente dès que son signal est abandonné, sans attendre le détenteur', async () => {
  const first = holder();
  const controller = new AbortController();
  const waiting = request(() => Promise.resolve('jamais'), controller.signal);

  controller.abort(new Error('abandon'));

  await expect(waiting).rejects.toThrow('abandon');
  first.release();
  await first.running;
});

it('ne donne pas le verrou à une demande abandonnée pendant son attente', async () => {
  const first = holder();
  const controller = new AbortController();
  const abandoned = request(() => Promise.resolve('jamais'), controller.signal);
  const next = request(() => Promise.resolve('suivante'));

  controller.abort(new Error('abandon'));
  first.release();

  await expect(abandoned).rejects.toThrow('abandon');
  await expect(next).resolves.toBe('suivante');
});

it('rejette tout de suite une demande dont le signal est déjà abandonné', async () => {
  await expect(
    request(
      () => Promise.resolve('jamais'),
      AbortSignal.abort(new Error('déjà')),
    ),
  ).rejects.toThrow('déjà');
});

it('laisse finir l’opération d’une demande dont le signal est abandonné après l’octroi', async () => {
  const controller = new AbortController();
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let started!: () => void;
  const hasStarted = new Promise<void>((resolve) => {
    started = resolve;
  });
  const granted = request(async () => {
    started();
    await held;
    return 'terminée';
  }, controller.signal);

  await hasStarted;
  controller.abort(new Error('trop tard'));
  release();

  await expect(granted).resolves.toBe('terminée');
});
