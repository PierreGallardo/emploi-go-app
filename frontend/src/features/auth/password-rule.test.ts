import { describe, expect, it } from 'vitest';
import cases from '../../../../contracts/password-rule-cases.json';
import { z } from 'zod';
import { missingCriteria, passwordIssue } from './password-rule';
import {
  credentialsSchema,
  resetPasswordSchema,
  signupSchema,
} from './schemas';

const sharedCases = z
  .object({
    cases: z
      .array(
        z.object({
          name: z.string(),
          password: z.string(),
          accepted: z.boolean(),
          missing: z.array(z.string()),
        }),
      )
      .min(1),
  })
  .parse(cases).cases;
const identity = {
  email: 'fictif@example.invalid',
  username: 'jeune_fictif',
  acceptedTerms: true,
  rememberMe: false,
};

describe('cas communs avec le backend (contracts/password-rule-cases.json)', () => {
  it.each(sharedCases)('$name', ({ password, accepted, missing }) => {
    expect(missingCriteria(password)).toEqual(missing);
    const values = { ...identity, password, confirmation: password };
    expect(signupSchema.safeParse(values).success).toBe(accepted);
    expect(resetPasswordSchema.safeParse(values).success).toBe(accepted);
  });
});

describe('message d’erreur du champ', () => {
  it.each([
    {
      saisie: 'abcdefg',
      manque:
        'Il manque : au moins 8 caractères, une majuscule, un chiffre, un symbole.',
    },
    {
      saisie: 'Abc1',
      manque: 'Il manque : au moins 8 caractères, un symbole.',
    },
    { saisie: 'ABCDEFG1!', manque: 'Il manque : une minuscule.' },
    { saisie: 'abcdefg1!', manque: 'Il manque : une majuscule.' },
    { saisie: 'Abcdefgh!', manque: 'Il manque : un chiffre.' },
  ])('liste seulement ce qui manque à « $saisie »', ({ saisie, manque }) => {
    expect(passwordIssue(saisie)).toBe(manque);
  });

  it('ne dit plus rien quand la règle est respectée', () => {
    expect(passwordIssue('Abcdef1!')).toBeUndefined();
  });

  it('distingue le champ vide et l’excès de longueur', () => {
    expect(passwordIssue('')).toBe('Choisis un mot de passe.');
    expect(passwordIssue('Ab1!'.repeat(33))).toBe(
      'Le mot de passe est limité à 128 caractères.',
    );
  });
});

it('ne demande rien de plus à la connexion : un ancien mot de passe de 12 caractères passe', () => {
  expect(
    credentialsSchema.safeParse({
      identifier: 'jeune',
      password: 'abcdefghijkl',
    }).success,
  ).toBe(true);
});
