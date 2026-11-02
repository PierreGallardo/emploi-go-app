import { afterEach, expect, it, vi } from 'vitest';
import { startDeadline, withDeadline } from './deadline';

afterEach(() => {
  vi.useRealTimers();
});

/** Only the timers the deadline arms: the clock of the test runner keeps its own time. */
const fakeDeadlineTimers = () =>
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });

it('abandonne à l’échéance avec une raison de délai, et se dit expirée', () => {
  fakeDeadlineTimers();
  const deadline = startDeadline(15_000);

  vi.advanceTimersByTime(14_999);
  expect(deadline.signal.aborted).toBe(false);
  expect(deadline.expired).toBe(false);

  vi.advanceTimersByTime(1);
  expect(deadline.signal.aborted).toBe(true);
  expect(deadline.signal.reason).toMatchObject({ name: 'TimeoutError' });
  expect(deadline.expired).toBe(true);
});

it('relaie la raison de l’appelant sans se dire expirée, même si l’échéance passe ensuite', () => {
  fakeDeadlineTimers();
  const caller = new AbortController();
  const deadline = startDeadline(15_000, caller.signal);
  const reason = new Error('annulée');

  caller.abort(reason);
  vi.advanceTimersByTime(15_000);

  expect(deadline.signal.reason).toBe(reason);
  expect(deadline.expired).toBe(false);
});

it('est déjà abandonnée quand le signal de l’appelant l’est', () => {
  const reason = new Error('déjà annulée');

  const deadline = startDeadline(15_000, AbortSignal.abort(reason));
  deadline.release();

  expect(deadline.signal.reason).toBe(reason);
  expect(deadline.expired).toBe(false);
});

it('ne laisse ni minuterie ni écoute une fois le travail terminé', () => {
  fakeDeadlineTimers();
  const caller = new AbortController();
  const deadline = startDeadline(15_000, caller.signal);

  deadline.release();
  caller.abort(new Error('trop tard'));
  vi.advanceTimersByTime(15_000);

  expect(vi.getTimerCount()).toBe(0);
  expect(deadline.signal.aborted).toBe(false);
});

it('abandonne le signal de l’opération à l’échéance, avec une raison de délai', async () => {
  fakeDeadlineTimers();
  const outcome = withDeadline(
    15_000,
    undefined,
    (signal) =>
      new Promise<string>((_, reject) => {
        signal.addEventListener('abort', () => reject(signal.reason));
      }),
  ).catch((failure: unknown) => failure);

  await vi.advanceTimersByTimeAsync(15_000);

  expect(await outcome).toMatchObject({ name: 'TimeoutError' });
  expect(vi.getTimerCount()).toBe(0);
});

it('fait échouer l’opération avec la raison de l’appelant quand il abandonne pendant son travail', async () => {
  fakeDeadlineTimers();
  const caller = new AbortController();
  const reason = new Error('annulée');
  const outcome = withDeadline(
    15_000,
    caller.signal,
    (signal) =>
      new Promise<string>((_, reject) => {
        signal.addEventListener('abort', () => reject(signal.reason));
      }),
  ).catch((failure: unknown) => failure);

  caller.abort(reason);

  expect(await outcome).toBe(reason);
});

it('rend le résultat de l’opération et ne laisse ni minuterie ni écoute', async () => {
  fakeDeadlineTimers();
  const caller = new AbortController();

  const result = await withDeadline(15_000, caller.signal, async () => 'lu');
  caller.abort(new Error('trop tard'));

  expect(result).toBe('lu');
  expect(vi.getTimerCount()).toBe(0);
});

it('libère l’échéance quand l’opération échoue', async () => {
  fakeDeadlineTimers();

  await expect(
    withDeadline(15_000, undefined, () => Promise.reject(new Error('refus'))),
  ).rejects.toThrow('refus');

  expect(vi.getTimerCount()).toBe(0);
});
