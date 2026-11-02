import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { Route, Routes } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  rateLimitedResponse,
} from '@/test/handlers/auth';
import { lastStatus, readsSessionAs } from '@/test/auth';
import { renderWithProviders } from '@/test/render';
import { recordRequests } from '@/test/requests';
import { server } from '@/test/server';
import { AuthenticatedScreen } from '../../account/components/authenticated-screen';
import { LoginScreen } from './login-screen';

afterEach(() => {
  vi.useRealTimers();
});

const screens = (
  <Routes>
    <Route path="/connexion" element={<LoginScreen />} />
    <Route
      path="/"
      element={
        <AuthenticatedScreen title="Carte">
          <h1>Carte</h1>
        </AuthenticatedScreen>
      }
    />
  </Routes>
);

async function showForm(identifier = 'jeune_demo') {
  readsSessionAs(anonymousResponse);
  renderWithProviders(screens, { route: '/connexion' });
  await screen.findByLabelText('Email ou pseudo');
  const typing = userEvent.setup();
  await typing.type(screen.getByLabelText('Email ou pseudo'), identifier);
  await typing.type(screen.getByLabelText('Mot de passe'), 'phrase fictive');
  // The clock stops at 14:30:10 and runs on: the screen and `user-event` wait on their own, and the wait is a timer.
  vi.useFakeTimers({
    now: new Date(2026, 9, 17, 14, 30, 10),
    shouldAdvanceTime: true,
    toFake: ['Date', 'setTimeout', 'clearTimeout'],
  });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  return { user, requests: recordRequests() };
}

type User = ReturnType<typeof userEvent.setup>;

const submitButton = () => screen.getByRole('button', { name: 'Se connecter' });

async function submitAnswer(user: User, answer: () => Response) {
  server.use(http.post('/api/auth/login', answer));
  await user.click(submitButton());
}

/** Submits once; the API answers 429 with a wait of `seconds`. */
async function submitLimited(user: User, seconds: number) {
  await submitAnswer(user, () => rateLimitedResponse(seconds));
  // The alert of an earlier refusal may still be there: the hint is the sign of this one.
  await screen.findByText(/Tu pourras réessayer/);
}

/** Submits once; the API answers 429 without saying how long to wait. */
async function submitWithoutWait(user: User) {
  await submitAnswer(user, () => problemResponse(429, 'RATE_LIMITED'));
  await screen.findByText(/Tu pourras réessayer/);
}

it('laisse saisir pendant le délai', async () => {
  const { user } = await showForm();
  await submitLimited(user, 30);

  await user.click(screen.getByLabelText('Mot de passe'));
  await user.paste(' complétée');

  expect(screen.getByLabelText('Mot de passe')).toHaveValue(
    'phrase fictive complétée',
  );
  expect(screen.getByRole('alert')).toHaveTextContent('Trop de tentatives');
});

it('connecte à l’échéance avec le même bouton', async () => {
  const { user, requests } = await showForm();
  await submitLimited(user, 5);
  await act(() => vi.advanceTimersByTimeAsync(5_000));
  server.resetHandlers();
  readsSessionAs(anonymousResponse);

  await user.click(submitButton());

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  await vi.waitFor(() =>
    expect(requests.of('POST', '/api/auth/login')).toHaveLength(2),
  );
});

it('attend une minute quand le 429 ne dit pas combien de temps', async () => {
  const { user } = await showForm();
  await submitWithoutWait(user);

  // The clock also runs in real time: the check stops 10 s before the deadline, as in the rounding case.
  await act(() => vi.advanceTimersByTimeAsync(50_000));
  expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
  expect(submitButton()).toHaveAccessibleDescription(
    'Tu pourras réessayer à 14:32.',
  );

  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(submitButton()).not.toHaveAttribute('aria-disabled', 'true');
});

it('relance l’attente quand l’API refuse encore à l’échéance', async () => {
  const { user } = await showForm();
  await submitLimited(user, 30);
  await act(() => vi.advanceTimersByTimeAsync(30_000));

  await submitLimited(user, 120);

  expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
  expect(submitButton()).toHaveAccessibleDescription(
    'Tu pourras réessayer à 14:33.',
  );
  expect(lastStatus()).toBeEmptyDOMElement();
});

it('lève l’attente et la région d’annonce quand le refus suivant n’est plus une limite', async () => {
  const { user } = await showForm();
  await submitLimited(user, 30);
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  server.use(
    http.post('/api/auth/login', () =>
      problemResponse(401, 'INVALID_CREDENTIALS'),
    ),
  );

  await user.click(submitButton());

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Identifiant ou mot de passe incorrect.',
  );
  expect(submitButton()).not.toHaveAttribute('aria-disabled', 'true');
  // Only the status of the loading is left: the wait has no region once the refusal is another one.
  expect(screen.getAllByRole('status')).toHaveLength(1);
  expect(screen.queryByText('Tu peux réessayer.')).not.toBeInTheDocument();
});

it.each([
  { seconds: 20, shown: '14:31' },
  { seconds: 40, shown: '14:31' },
  { seconds: 51, shown: '14:32' },
])(
  'arrondit l’heure à la minute supérieure : $seconds s après 14:30:10 donnent $shown',
  async ({ seconds, shown }) => {
    const { user } = await showForm();

    await submitLimited(user, seconds);

    expect(submitButton()).toHaveAccessibleDescription(
      `Tu pourras réessayer à ${shown}.`,
    );
    // The button works again at the exact deadline, not at the minute shown. The clock also runs in real time, so the check stops 10 s early.
    await act(() => vi.advanceTimersByTimeAsync(seconds * 1000 - 10_000));
    expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    expect(submitButton()).not.toHaveAttribute('aria-disabled', 'true');
  },
);

it('retient de nouveau le bouton quand l’identifiant redevient celui du délai', async () => {
  const { user } = await showForm();
  await submitLimited(user, 300);
  const identifier = screen.getByLabelText('Email ou pseudo');
  await user.clear(identifier);
  await user.type(identifier, 'autre-jeune');
  expect(submitButton()).not.toHaveAttribute('aria-disabled', 'true');

  await user.clear(identifier);
  await user.type(identifier, 'jeune_demo');

  expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
  expect(submitButton()).toHaveAccessibleDescription(
    'Tu pourras réessayer à 14:36.',
  );
});
