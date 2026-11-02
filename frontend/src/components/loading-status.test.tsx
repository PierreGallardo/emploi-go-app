import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useFocusTracking } from '@/lib/lost-focus';
import { endOfLoadingDuration, LoadingStatus } from './loading-status';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
});
afterEach(() => {
  vi.useRealTimers();
});

it('dit « Chargement… » tant que le chargement dure', () => {
  render(<LoadingStatus loading />);

  expect(screen.getByRole('status')).toHaveTextContent('Chargement…');
});

it('annonce la fin dans le même élément, puis le retire à la fin de sa durée', async () => {
  const { rerender } = render(<LoadingStatus loading />);
  const status = screen.getByRole('status');

  rerender(<LoadingStatus loading={false} />);
  await act(async () => {});
  expect(screen.getByRole('status')).toBe(status);
  expect(status).toHaveTextContent('Chargement terminé.');

  await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration - 1));
  expect(status).toHaveTextContent('Chargement terminé.');
  await act(() => vi.advanceTimersByTimeAsync(1));
  expect(status).toHaveTextContent(/^$/);
});

it('ne dit rien quand le chargement s’est terminé sur une erreur', async () => {
  const { rerender } = render(<LoadingStatus loading />);

  rerender(<LoadingStatus loading={false} silent />);
  await act(async () => {});

  expect(screen.getByRole('status')).toHaveTextContent(/^$/);
});

it('annonce de nouveau la fin d’un second chargement', async () => {
  const { rerender } = render(<LoadingStatus loading />);
  rerender(<LoadingStatus loading={false} />);
  await act(async () => {});
  await act(() => vi.advanceTimersByTimeAsync(endOfLoadingDuration));
  expect(screen.getByRole('status')).toHaveTextContent(/^$/);

  rerender(<LoadingStatus loading />);
  expect(screen.getByRole('status')).toHaveTextContent('Chargement…');
  rerender(<LoadingStatus loading={false} />);
  await act(async () => {});
  expect(screen.getByRole('status')).toHaveTextContent('Chargement terminé.');
});

function ButtonThenStatus() {
  useFocusTracking();
  const [done, setDone] = useState(false);
  return (
    <>
      {done ? null : <button onClick={() => setDone(true)}>Continuer</button>}
      <LoadingStatus loading={!done} />
    </>
  );
}

describe('quand le focus quitte la page avec le chargement', () => {
  // userEvent waits on its own timers, which the fake clock above would hold.
  beforeEach(() => {
    vi.useRealTimers();
  });

  it('ne dit rien quand l’élément qui avait le focus a quitté la page avec le chargement', async () => {
    render(<ButtonThenStatus />);
    await userEvent.setup().click(screen.getByRole('button'));
    await act(async () => {});

    expect(screen.queryByText('Chargement terminé.')).not.toBeInTheDocument();
  });
});
