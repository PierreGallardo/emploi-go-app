import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import usernameCases from '../../../../contracts/username-rule-cases.json';
import {
  registrationCodeSchema,
  usernameSchema,
  resetPasswordSchema,
  signupSchema,
} from './schemas';
import { usernameRule } from './username-rule';

const identity = {
  email: 'fictif@example.invalid',
  acceptedTerms: true,
  rememberMe: false,
};

it.each([
  { cas: 'courant', password: 'V7!rQ2#mL9@x' },
  {
    cas: 'avec espaces, accent et emoji',
    password: '  Arbres près du canal 4 🌳  ',
  },
])(
  'accepte un mot de passe conforme $cas à l’inscription et à la réinitialisation',
  ({ password }) => {
    const values = { ...identity, password, confirmation: password };
    expect(signupSchema.parse(values).password).toBe(password);
    expect(resetPasswordSchema.parse(values).password).toBe(password);
  },
);

it.each([
  { parcours: 'd’inscription', schema: signupSchema },
  { parcours: 'de changement de mot de passe', schema: resetPasswordSchema },
])(
  'refuse une confirmation différente au parcours $parcours, avec le message du champ',
  ({ schema }) => {
    const values = {
      ...identity,
      password: 'V7!rQ2#mL9@x',
      confirmation: 'V7!rQ2#mL9@y',
    };
    expect(schema.safeParse(values).error?.issues).toEqual([
      expect.objectContaining({
        path: ['confirmation'],
        message: 'Les mots de passe doivent être identiques.',
      }),
    ]);
  },
);

it('signale aussi la confirmation différente quand le mot de passe ne respecte pas la règle', () => {
  const result = signupSchema.safeParse({
    ...identity,
    password: 'abc',
    confirmation: 'abd',
  });
  expect(result.success).toBe(false);
  expect(
    result.error?.issues.map((issue) => [issue.path.join('.'), issue.message]),
  ).toEqual([
    [
      'password',
      'Il manque : au moins 8 caractères, une majuscule, un chiffre, un symbole.',
    ],
    ['confirmation', 'Les mots de passe doivent être identiques.'],
  ]);
});

it.each(['', '12345', '1234567', '12345a', '١٢٣٤٥٦'])(
  'refuse le code « %s »',
  (code) => {
    expect(registrationCodeSchema.safeParse({ code }).success).toBe(false);
  },
);

const sharedUsernameCases = z
  .object({
    cases: z
      .array(
        z.object({
          name: z.string(),
          input: z.string(),
          accepted: z.boolean(),
        }),
      )
      .min(1),
  })
  .parse(usernameCases).cases;

describe('cas communs avec le backend (contracts/username-rule-cases.json)', () => {
  it.each(sharedUsernameCases)('$name', ({ input, accepted }) => {
    expect(usernameSchema.safeParse({ username: input }).success).toBe(
      accepted,
    );
  });
});

it.each([
  { cas: 'vide', saisie: ' ', message: 'Choisis un pseudo.' },
  { cas: 'hors règle', saisie: 'léa', message: usernameRule },
])('refuse un pseudo $cas avec son message', ({ saisie, message }) => {
  const result = usernameSchema.safeParse({ username: saisie });
  expect(result.error?.issues.map((issue) => issue.message)).toEqual([message]);
});
