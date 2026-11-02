/** Same rule as the API (`RegistrationPassword`, `contracts/password-rule-cases.json`); lengths count UTF-16 units, and a special character is neither a letter, a digit nor a combining mark. */
const passwordMinLength = 8;
const passwordMaxLength = 128;

type PasswordCriterionId =
  'length' | 'uppercase' | 'lowercase' | 'digit' | 'special';

interface PasswordCriterion {
  readonly id: PasswordCriterionId;
  /** What the error lists while the criterion is not met; it does not change while typing, or the alert would interrupt at each key. */
  readonly missing: string;
  readonly met: (password: string) => boolean;
}

const passwordCriteria: readonly PasswordCriterion[] = [
  {
    id: 'length',
    missing: `au moins ${passwordMinLength} caractères`,
    met: (password) =>
      password.length >= passwordMinLength &&
      password.length <= passwordMaxLength,
  },
  {
    id: 'uppercase',
    missing: 'une majuscule',
    met: (password) => /\p{Lu}/u.test(password),
  },
  {
    id: 'lowercase',
    missing: 'une minuscule',
    met: (password) => /\p{Ll}/u.test(password),
  },
  {
    id: 'digit',
    missing: 'un chiffre',
    met: (password) => /\p{Nd}/u.test(password),
  },
  {
    id: 'special',
    missing: 'un symbole',
    met: (password) => /[^\p{L}\p{M}\p{Nd}]/u.test(password),
  },
];

export const passwordHint = `${passwordMinLength} caractères minimum, avec une majuscule, une minuscule, un chiffre et un symbole.`;
export const passwordCompliantMessage = 'Ton mot de passe respecte la règle.';
export const weakPasswordMessage = `Ce mot de passe ne respecte pas la règle : ${passwordMinLength} à ${passwordMaxLength} caractères, avec une majuscule, une minuscule, un chiffre et un caractère spécial.`;

const unmet = (password: string) =>
  passwordCriteria.filter((criterion) => !criterion.met(password));

export function missingCriteria(password: string) {
  return unmet(password).map((criterion) => criterion.id);
}

/** Lists only what is missing: the alert changes, and is announced again, when a criterion becomes met. */
export function passwordIssue(password: string) {
  if (password.length === 0) return 'Choisis un mot de passe.';
  if (password.length > passwordMaxLength)
    return `Le mot de passe est limité à ${passwordMaxLength} caractères.`;
  const missing = unmet(password).map((criterion) => criterion.missing);
  return missing.length > 0 ? `Il manque : ${missing.join(', ')}.` : undefined;
}
