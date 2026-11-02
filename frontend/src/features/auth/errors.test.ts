import { expect, it } from 'vitest';
import {
  AuthenticationCoordinationError,
  AuthenticationError,
  authenticationMessage,
  recoveryMessage,
  registrationCodeMessage,
  registrationMessage,
} from './errors';
import { googleMessage } from './google-errors';

it.each([
  {
    cas: 'quota dépassé',
    status: 429,
    code: 'RATE_LIMITED',
    message: 'Trop de tentatives. Réessaie plus tard.',
  },
  {
    cas: 'compteurs indisponibles',
    status: 503,
    code: 'UNAVAILABLE',
    message: 'Connexion temporairement indisponible. Réessaie plus tard.',
  },
])('annonce $cas sans durée fixe', ({ status, code, message }) => {
  expect(authenticationMessage(new AuthenticationError(status, code))).toBe(
    message,
  );
});

it('dit de regarder ses emails quand un code a déjà été demandé pour cette adresse', () => {
  expect(
    registrationCodeMessage(
      new AuthenticationError(429, 'MAIL_RECENTLY_SENT', 45),
    ),
  ).toBe(
    'Un code a déjà été demandé pour cette adresse il y a moins d’une minute. Regarde tes emails.',
  );
});

it('dit qu’un lien déjà servi peut être remplacé par la connexion', () => {
  expect(recoveryMessage(new AuthenticationError(410, 'USED_LINK'))).toBe(
    'Ce lien a déjà servi. Si tu viens de changer ton mot de passe, connecte-toi avec le nouveau.',
  );
});

it.each([
  {
    cas: 'un délai dépassé',
    error: new AuthenticationCoordinationError('timeout'),
    message:
      'Cette action a pris trop de temps. Vérifie ta connexion puis réessaie.',
  },
  {
    cas: 'un navigateur sans coordination entre onglets',
    error: new AuthenticationCoordinationError(),
    message: 'Utilise un navigateur récent et une connexion sécurisée (HTTPS).',
  },
])(
  'explique $cas dans tous les messages d’authentification, Google compris',
  ({ error, message }) => {
    expect(authenticationMessage(error)).toBe(message);
    expect(registrationMessage(error)).toBe(message);
    expect(recoveryMessage(error)).toBe(message);
    expect(googleMessage(error)).toBe(message);
  },
);

it('dit la règle quand l’API refuse le mot de passe, avec les mêmes mots que l’API', () => {
  const error = new AuthenticationError(400, 'WEAK_PASSWORD');
  expect(registrationMessage(error)).toBe(
    'Ce mot de passe ne respecte pas la règle : 8 à 128 caractères, avec une majuscule, une minuscule, un chiffre et un caractère spécial.',
  );
  expect(recoveryMessage(error)).toBe(registrationMessage(error));
});

it('dit la règle quand l’API refuse le pseudo, avec les mêmes mots que l’API', () => {
  const error = new AuthenticationError(422, 'INVALID_USERNAME');
  expect(registrationMessage(error)).toBe(
    '2 à 32 caractères : lettres sans accent, chiffres, _ et .',
  );
  expect(googleMessage(error)).toBe(registrationMessage(error));
});

it.each([
  {
    code: 'INVALID_CODE',
    status: 400,
    message: 'Code incorrect. Vérifie le dernier email reçu.',
  },
  {
    code: 'CODE_EXPIRED',
    status: 410,
    message: 'Code expiré. Demande un nouveau code.',
  },
  {
    code: 'CODE_REPLACED',
    status: 410,
    message: 'Ce code a été remplacé. Utilise le dernier email reçu.',
  },
  {
    code: 'CODE_USED',
    status: 410,
    message: 'Code déjà utilisé. Connecte-toi.',
  },
  {
    code: 'CODE_ATTEMPTS_EXCEEDED',
    status: 410,
    message: 'Trop d’essais. Demande un nouveau code.',
  },
])('dit « $message » pour le refus $code', ({ code, status, message }) => {
  expect(registrationCodeMessage(new AuthenticationError(status, code))).toBe(
    message,
  );
});

it('parle d’essais, sans durée, quand les codes sont bloqués une heure', () => {
  expect(
    registrationCodeMessage(
      new AuthenticationError(429, 'CODES_BLOCKED', 3600),
    ),
  ).toBe('Trop d’essais.');
});

it('garde le message de quota pour une limite qui n’est pas le blocage', () => {
  expect(
    registrationCodeMessage(new AuthenticationError(429, 'RATE_LIMITED', 3600)),
  ).toBe('Trop de tentatives. Réessaie plus tard.');
});

it('ne prend pas un nom hérité de l’objet pour un code connu', () => {
  expect(registrationMessage(new AuthenticationError(400, 'constructor'))).toBe(
    'Impossible de terminer cette action. Réessaie.',
  );
});

const busyMessage =
  'Le service est très sollicité. Réessaie dans quelques secondes.';

it.each([
  {
    parcours: 'l’inscription',
    message: (error: AuthenticationError) => registrationMessage(error),
  },
  {
    parcours: 'la récupération',
    message: (error: AuthenticationError) => recoveryMessage(error),
  },
  {
    parcours: 'la connexion',
    message: (error: AuthenticationError) => authenticationMessage(error),
  },
])(
  'annonce le délai de quelques secondes du plafond de calculs pour $parcours',
  ({ message }) => {
    expect(message(new AuthenticationError(503, 'BUSY'))).toBe(busyMessage);
  },
);

it.each([
  {
    parcours: 'l’inscription',
    message: (error: AuthenticationError) => registrationMessage(error),
    attendu:
      'Action temporairement indisponible. Attends une minute puis réessaie.',
  },
  {
    parcours: 'la récupération',
    message: (error: AuthenticationError) => recoveryMessage(error),
    attendu:
      'Action temporairement indisponible. Attends une minute puis réessaie.',
  },
  {
    parcours: 'la connexion',
    message: (error: AuthenticationError) => authenticationMessage(error),
    attendu: 'Connexion temporairement indisponible. Réessaie plus tard.',
  },
])(
  'garde le message de panne d’une indisponibilité qui n’est pas le plafond de calculs pour $parcours',
  ({ message, attendu }) => {
    expect(message(new AuthenticationError(503, 'UNAVAILABLE'))).toBe(attendu);
  },
);
