/** Same rule as the API (`AccountIdentifier`, `contracts/username-rule-cases.json`), which stores the username in lower case. */
export const usernamePattern = /^(?!.*\.\.)[A-Za-z0-9_.]{2,32}$/;
export const usernameRule =
  '2 à 32 caractères : lettres sans accent, chiffres, _ et .';
