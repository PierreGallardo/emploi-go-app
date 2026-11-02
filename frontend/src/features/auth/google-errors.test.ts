import { expect, it } from 'vitest';
import { AuthenticationError } from './errors';
import { googleCodeMessage, googleMessage } from './google-errors';
import { usernameRule } from './username-rule';

it('garde le message de Google pour une panne sans cause connue', () => {
  expect(googleMessage(new TypeError('fetch failed'))).toBe(
    'Google est temporairement indisponible. Réessaie ou utilise ton mot de passe.',
  );
});

it.each([
  {
    code: 'CANCELLED',
    message: 'La connexion Google a été annulée. Tu peux réessayer.',
  },
  {
    code: 'INVALID_FLOW',
    message: 'Cette demande a expiré ou a changé. Recommence avec Google.',
  },
  {
    code: 'EXPIRED_FLOW',
    message: 'Cette demande a expiré ou a changé. Recommence avec Google.',
  },
  {
    code: 'PROVIDER_REJECTED',
    message:
      'Google n’a pas pu vérifier la connexion. Recommence ou utilise ton mot de passe.',
  },
  {
    code: 'ACCOUNT_ACCESS_REQUIRED',
    message: 'Connecte-toi à ton compte Emploi Go pour y associer Google.',
  },
  {
    code: 'EMAIL_CONFIRMATION_REQUIRED',
    message: 'Confirme d’abord ton email, puis reprends Google.',
  },
  {
    code: 'ALREADY_LINKED',
    message:
      'Cette association n’est plus disponible. Recommence avec la méthode habituelle du compte.',
  },
  { code: 'INVALID_USERNAME', message: usernameRule },
  { code: 'USERNAME_UNAVAILABLE', message: 'Ce pseudo est déjà pris.' },
  {
    code: 'INVALID_LINK',
    message:
      'Ce lien est invalide. Reviens à Google pour demander un nouveau lien.',
  },
  {
    code: 'EXPIRED_LINK',
    message:
      'Ce lien a expiré. Reviens à Google pour demander un nouveau lien.',
  },
  {
    code: 'USED_LINK',
    message: 'Ce lien a déjà été utilisé. Continue avec Google.',
  },
  {
    code: 'REPLACED_LINK',
    message: 'Ce lien a été remplacé. Utilise le dernier lien reçu.',
  },
])('explique le code Google $code au retour de Google', ({ code, message }) => {
  expect(googleCodeMessage(code)).toBe(message);
});

it.each([
  {
    cas: 'un quota dépassé',
    code: 'RATE_LIMITED',
    message:
      'Trop de tentatives avec Google. Réessaie dans quelques minutes ou utilise ton mot de passe.',
  },
  {
    cas: 'un service indisponible',
    code: 'UNAVAILABLE',
    message:
      'Le service est momentanément indisponible. Réessaie dans quelques instants ou utilise ton mot de passe.',
  },
])(
  'annonce $cas au retour de Google sans promettre une cause',
  ({ code, message }) => {
    expect(googleCodeMessage(code)).toBe(message);
  },
);

it.each([
  {
    cas: 'un quota dépassé',
    error: new AuthenticationError(429, 'RATE_LIMITED'),
    message: 'Trop de tentatives. Réessaie plus tard.',
  },
  {
    cas: 'une indisponibilité',
    error: new AuthenticationError(503, 'UNAVAILABLE'),
    message: 'Connexion temporairement indisponible. Réessaie plus tard.',
  },
  {
    cas: 'le plafond de calculs',
    error: new AuthenticationError(503, 'BUSY'),
    message: 'Le service est très sollicité. Réessaie dans quelques secondes.',
  },
])('garde le message de la requête pour $cas', ({ error, message }) => {
  expect(googleMessage(error)).toBe(message);
});
