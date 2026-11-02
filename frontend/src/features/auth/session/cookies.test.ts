import { expect, it } from 'vitest';
import { cookieNames, readCookie } from './cookies';

it.each([
  {
    cas: 'HTTPS (production)',
    protocol: 'https:',
    csrf: '__Host-XSRF-TOKEN',
    reconnectable: '__Host-EMPLOIGO_RECONNECTABLE',
  },
  {
    cas: 'HTTP (profil local)',
    protocol: 'http:',
    csrf: 'XSRF-TOKEN',
    reconnectable: 'EMPLOIGO_RECONNECTABLE',
  },
])('nomme les cookies pour $cas', ({ protocol, csrf, reconnectable }) => {
  expect(cookieNames(protocol)).toEqual({ csrf, reconnectable });
});

it.each([
  { cas: 'présent', cookies: 'a=1; XSRF-TOKEN=jeton; b=2', value: 'jeton' },
  { cas: 'seul', cookies: 'XSRF-TOKEN=jeton', value: 'jeton' },
  { cas: 'absent', cookies: 'a=1; b=2', value: undefined },
  { cas: 'vide', cookies: '', value: undefined },
  { cas: 'valeur avec =', cookies: 'XSRF-TOKEN=a=b', value: 'a=b' },
  {
    cas: 'nom préfixé par un autre',
    cookies: '__Host-XSRF-TOKEN=jeton',
    value: undefined,
  },
  {
    cas: 'nom précédé d’une espace Unicode',
    cookies: '\u2000XSRF-TOKEN=planté',
    value: undefined,
  },
  {
    cas: 'nom suffixé par un autre',
    cookies: 'XSRF-TOKEN-OLD=jeton',
    value: undefined,
  },
])('lit un cookie $cas', ({ cookies, value }) => {
  expect(readCookie('XSRF-TOKEN', cookies)).toBe(value);
});
