import { expect, it } from 'vitest';
import { readRegistrationCodeState } from './registration-code-state';

const registration = 'inscription-fictive';

it.each([
  { cas: 'cochée', rememberMe: true, relue: true },
  { cas: 'décochée', rememberMe: false, relue: false },
  { cas: 'absente', rememberMe: undefined, relue: false },
  { cas: 'en texte', rememberMe: 'true', relue: false },
])(
  'relit un état valide, case « Rester connecté » $cas',
  ({ rememberMe, relue }) => {
    expect(
      readRegistrationCodeState({
        email: 'jeune@exemple.fr',
        rememberMe,
        registration,
      }),
    ).toEqual({ email: 'jeune@exemple.fr', rememberMe: relue, registration });
  },
);

it.each([
  null,
  undefined,
  'texte',
  {},
  { email: 'jeune@exemple.fr' },
  { email: 'jeune@exemple.fr', registration: '' },
  { email: 'jeune@exemple.fr', registration: 42 },
  { email: '  ', registration },
  { email: 'pas-un-email', registration },
  { email: 42, registration },
])('refuse l’état %j', (state) => {
  expect(readRegistrationCodeState(state)).toBeNull();
});

it('garde une échéance de renvoi dans la limite d’une heure', () => {
  const resendAt = Date.now() + 60_000;

  expect(
    readRegistrationCodeState({
      email: 'jeune@exemple.fr',
      registration,
      resendAt,
    }),
  ).toEqual({
    email: 'jeune@exemple.fr',
    rememberMe: false,
    registration,
    resendAt,
  });
});

it.each([
  { cas: 'au-delà d’une heure', resendAt: Date.now() + 2 * 3_600_000 },
  { cas: 'infinie', resendAt: Infinity },
  { cas: 'non numérique', resendAt: NaN },
  { cas: 'en texte', resendAt: '60' },
])('ignore une échéance de renvoi $cas', ({ resendAt }) => {
  expect(
    readRegistrationCodeState({
      email: 'jeune@exemple.fr',
      registration,
      resendAt,
    }),
  ).toEqual({ email: 'jeune@exemple.fr', rememberMe: false, registration });
});
