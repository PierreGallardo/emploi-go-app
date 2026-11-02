import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  readXlsxSheet,
  readZip,
  requireEntry,
  writeAtomically,
} from './open-data.ts';
import { workbookOf, zipOf } from './zip-fixture.ts';

test('lit un fichier d’une archive zip', () => {
  const zip = readZip(zipOf({ 'a.txt': 'bonjour', 'b.txt': 'salut' }));

  assert.equal(requireEntry(zip, 'b.txt'), 'salut');
});

test('refuse un fichier absent de l’archive', () => {
  assert.throws(
    () => requireEntry(readZip(zipOf({ 'a.txt': 'x' })), 'b.txt'),
    /b\.txt/,
  );
});

test('refuse un fichier qui n’est pas une archive', () => {
  assert.throws(() => readZip(Buffer.from('pas un zip')), /Invalid zip/);
});

test('lit les lignes d’une feuille xlsx avec leur niveau de plan', () => {
  const zip = readZip(
    workbookOf('Feuille', [
      [0, { A: '5', B: 'Division fictive' }],
      [1, { A: 'X0001', B: 'Métier & fiction' }],
    ]),
  );

  assert.deepEqual(readXlsxSheet(zip, 'Feuille'), [
    { outline: 0, cells: { A: '5', B: 'Division fictive' } },
    { outline: 1, cells: { A: 'X0001', B: 'Métier & fiction' } },
  ]);
});

test('signale une feuille xlsx introuvable', () => {
  const zip = readZip(workbookOf('Feuille', []));

  assert.throws(() => readXlsxSheet(zip, 'Absente'), /Absente/);
});

test('remplace un fichier généré par un résultat complet, sans laisser de fichier temporaire', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'open-data-'));
  try {
    const path = join(directory, 'sous', 'sortie.json');
    await writeAtomically(path, 'v1');
    await writeAtomically(path, 'v2');

    assert.equal(await readFile(path, 'utf8'), 'v2');
  } finally {
    await rm(directory, { recursive: true });
  }
});

test('garde l’ancien fichier si l’écriture échoue', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'open-data-'));
  try {
    const path = join(directory, 'sortie.json');
    await writeFile(path, 'ancien');

    await assert.rejects(writeAtomically(join(path, 'impossible'), 'x'));

    assert.equal(await readFile(path, 'utf8'), 'ancien');
  } finally {
    await rm(directory, { recursive: true });
  }
});
