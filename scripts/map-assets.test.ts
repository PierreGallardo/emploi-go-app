import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import {
  assertLocalStyle,
  assetChecksums,
  verifyMapAssets,
} from './map-assets.ts';

test('le lot livré conserve ses empreintes, ses couches réelles et son en-tête PMTiles', async () => {
  const result = await verifyMapAssets();
  assert.equal(result.files, 25);
  assert.equal(result.archiveBytes, 12734381);
});
test('le lot livré ne contient aucun point d’intérêt, ni dans l’archive ni dans le style', async () => {
  const root = new URL(
    '../frontend/public/cartes/dijon-20260930-v2/',
    import.meta.url,
  );
  const { vectorLayers } = JSON.parse(
    await readFile(new URL('manifest.json', root), 'utf8'),
  ) as { vectorLayers: string[] };
  const { layers } = JSON.parse(
    await readFile(new URL('style.json', root), 'utf8'),
  ) as { layers: Array<{ 'source-layer'?: string }> };
  // verifyMapAssets compares the manifest with the archive metadata and the style layers.
  await verifyMapAssets();
  assert.equal(vectorLayers.includes('pois'), false);
  assert.equal(
    layers.some((layer) => layer['source-layer'] === 'pois'),
    false,
  );
});
test('le contrôle de style refuse une ressource distante ou une couche étrangère', () => {
  const prefix = '/cartes/dijon-20260930-v2/';
  const style = {
    glyphs: prefix + 'fonts/{fontstack}/{range}.pbf',
    sprite: prefix + 'sprites/light',
    sources: { protomaps: { url: 'pmtiles://' + prefix + 'dijon.pmtiles' } },
    layers: [{ 'source-layer': 'roads' }],
  };
  assert.doesNotThrow(() => assertLocalStyle(style, ['roads']));
  assert.throws(() =>
    assertLocalStyle({ ...style, glyphs: 'https://example.invalid/font.pbf' }, [
      'roads',
    ]),
  );
  assert.throws(() =>
    assertLocalStyle(
      { ...style, layers: [{ 'source-layer': 'transportation' }] },
      ['roads'],
    ),
  );
});
test('les empreintes détectent une modification et excluent le manifeste lui-même', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'emploi-go-map-assets-'));
  try {
    await mkdir(join(dir, 'fonts'));
    await writeFile(join(dir, 'fonts', 'test.pbf'), 'glyph-fictif');
    await writeFile(join(dir, 'manifest.json'), '{}');
    const first = await assetChecksums(dir);
    assert.equal(first.length, 1);
    assert.equal(first[0]?.path, 'fonts/test.pbf');
    await writeFile(join(dir, 'fonts', 'test.pbf'), 'glyph-modifie');
    assert.notEqual((await assetChecksums(dir))[0]?.sha256, first[0]?.sha256);
  } finally {
    assert.equal(dirname(resolve(dir)), resolve(tmpdir()));
    assert.equal(basename(dir).startsWith('emploi-go-map-assets-'), true);
    await rm(dir, { recursive: true, force: true });
  }
});
