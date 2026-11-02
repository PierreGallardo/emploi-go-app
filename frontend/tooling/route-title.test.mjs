import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { ESLint } from 'eslint';

const eslint = new ESLint({
  cwd: fileURLToPath(new URL('..', import.meta.url)),
});

async function problems(code, filePath = 'src/routes/example.tsx') {
  const [result] = await eslint.lintText(code, { filePath });
  return result.messages.filter(
    ({ ruleId }) => ruleId === 'no-restricted-syntax',
  );
}

const refusedTitles = [
  [
    'un titre littéral dans meta() d’une route',
    "export function meta() { return [{ title: 'Connexion' }]; }",
  ],
  [
    'un titre entre guillemets dans meta() d’une route',
    "export function meta() { return [{ 'title': 'Connexion' }]; }",
  ],
  [
    'un titre après une autre balise dans meta()',
    "export function meta() { return [{ name: 'referrer' }, { title: 'x' }]; }",
  ],
  [
    'un titre dans meta() défini par une fonction fléchée',
    "export const meta = () => [{ title: 'Connexion' }];",
  ],
];

for (const [name, code] of refusedTitles) {
  test(`refuse ${name}`, async () => {
    assert.equal((await problems(code)).length, 1, code);
  });
}

const acceptedMeta = [
  [
    'une autre balise de meta()',
    "export function meta() { return [{ name: 'description', content: 'x' }]; }",
  ],
  [
    'un titre hors de meta()',
    "const page = { title: 'x' }; export default function Route() { return null; }",
  ],
];

for (const [name, code] of acceptedMeta) {
  test(`accepte ${name}`, async () => {
    assert.deepEqual(await problems(code), [], code);
  });
}

test('ne s’applique qu’aux routes', async () => {
  assert.deepEqual(
    await problems(
      "export function meta() { return [{ title: 'x' }]; }",
      'src/components/example.tsx',
    ),
    [],
  );
});
