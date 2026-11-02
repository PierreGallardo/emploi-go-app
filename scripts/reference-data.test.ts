import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { buildJobs, buildReferenceSql, generate } from './reference-data.ts';
import { workbookOf } from './zip-fixture.ts';

const naf2025 = workbookOf('Sous-classes', [
  [0, { A: 'NAF 2025 sous-classes', B: 'Intitulés' }],
  [0, { A: '56.11G', B: 'Restauration fictive' }],
  [0, { A: '95.31G', B: 'Réparation fictive d’automobiles' }],
]);
const correspondences = workbookOf('NAF rév. 2 vers NAF 2025', [
  [
    0,
    {
      A: 'Sous-classes NAF rév. 2 – codes',
      D: 'Sous-classes NAF 2025 – codes',
    },
  ],
  [0, { A: '56.10A', B: 'Restauration fictive', D: '56.11G' }],
  [0, { A: '45.20A', B: "Entretien fictif d'automobiles", D: '95.31G' }],
]);
const rome = workbookOf('Secteur NAF 10-2026', [
  [0, { A: '45', B: 'Division fictive' }],
  [1, { A: 'Z0001', B: 'Mécanicien fictif' }],
  [0, { A: '56', B: 'Autre division fictive' }],
  [1, { A: 'Z0002', B: 'Serveur fictif' }],
]);

test('écrit les sous-classes des deux nomenclatures, leurs divisions et leur correspondance', () => {
  const { sql, counts } = buildReferenceSql({ naf2025, correspondences, rome });

  assert.deepEqual(counts, {
    activities: 4,
    correspondences: 2,
    jobs: 2,
    jobDivisions: 2,
  });
  assert.match(sql, /\('NAF_REV2', '45'\)/);
  assert.match(sql, /\('NAF_2025', '95'\)/);
  assert.match(
    sql,
    /\('NAF_REV2', '45\.20A', 'Entretien fictif d''automobiles'\)/,
  );
  assert.match(sql, /\('45\.20A', '95\.31G'\)/);
  assert.match(sql, /\('Z0001', '45'\)/);
});

test('recalcule la correspondance et les statistiques à la fin de la migration', () => {
  const { sql } = buildReferenceSql({ naf2025, correspondences, rome });

  assert.match(
    sql,
    /REFRESH MATERIALIZED VIEW reference\.job_activities;\nANALYZE [^;]*reference\.job_activities;\n$/,
  );
});

test('refuse une correspondance vers une sous-classe NAF 2025 absente de la structure', () => {
  const broken = workbookOf('NAF rév. 2 vers NAF 2025', [
    [0, { A: '56.10A', B: 'Restauration fictive', D: '56.19Y' }],
  ]);

  assert.throws(
    () => buildReferenceSql({ naf2025, correspondences: broken, rome }),
    /56\.19Y/,
  );
});

test('garde la migration existante quand un fichier n’a pas la forme attendue', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'reference-'));
  try {
    const path = join(directory, 'V1__reference_data.sql');
    await writeFile(path, '-- migration précédente\n');
    const files = new Map([
      ['Structure', workbookOf('Sous-classes', [])],
      ['Correspondances', correspondences],
      ['rome', rome],
    ]);

    await assert.rejects(
      generate(
        path,
        async (url) => [...files].find(([name]) => url.includes(name))![1],
      ),
      { message: /Unknown NAF 2025 subclass in the correspondence/ },
    );
    assert.equal(await readFile(path, 'utf8'), '-- migration précédente\n');
  } finally {
    await rm(directory, { recursive: true });
  }
});

test('écrit la migration des données officielles', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'reference-'));
  try {
    const path = join(directory, 'V1__reference_data.sql');

    const files = new Map([
      ['Structure', naf2025],
      ['Correspondances', correspondences],
      ['rome', rome],
    ]);

    const counts = await generate(
      path,
      async (url) => [...files].find(([name]) => url.includes(name))![1],
    );

    assert.equal(counts.activities, 4);
    assert.match(await readFile(path, 'utf8'), /INSERT INTO reference\.jobs/);
  } finally {
    await rm(directory, { recursive: true });
  }
});

const romeWithTransverse = workbookOf('Secteur NAF 10-2026', [
  [0, { A: '0', B: 'Transverse aux secteurs' }],
  [1, { A: 'Z0000', B: 'Métier transverse fictif' }],
  [0, { A: '5', B: 'Division fictive' }],
  [1, { A: 'Z0001', B: 'Pilote fictif' }],
  [1, { A: 'Z0002', B: 'Mécanicien fictif' }],
  [0, { A: '56', B: 'Autre division fictive' }],
  [1, { A: 'Z0002', B: 'Mécanicien fictif' }],
]);

test('rattache chaque métier à sa division sur deux chiffres, sans le doubler', () => {
  const { jobs, divisions } = buildJobs(romeWithTransverse);

  assert.deepEqual(
    jobs.map(([code]) => code),
    ['Z0001', 'Z0002'],
  );
  assert.deepEqual(divisions, { '05': [0, 1], '56': [1] });
});

test('ignore les métiers transverses, rattachés à aucune division', () => {
  const { jobs } = buildJobs(romeWithTransverse);

  assert.equal(
    jobs.some(([code]) => code === 'Z0000'),
    false,
  );
});

test('refuse deux métiers du même libellé, que la carte ne saurait distinguer', () => {
  const twice = workbookOf('Secteur NAF 10-2026', [
    [0, { A: '56', B: 'Division fictive' }],
    [1, { A: 'Z0001', B: 'Serveur fictif' }],
    [1, { A: 'Z0002', B: 'Serveur fictif' }],
  ]);

  assert.throws(() => buildJobs(twice), /same label/);
});
