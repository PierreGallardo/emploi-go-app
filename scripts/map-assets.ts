import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';
import { bytesToHeader } from 'pmtiles';

const root = fileURLToPath(
  new URL('../frontend/public/cartes/dijon-20260930-v2/', import.meta.url),
);
type Asset = { path: string; bytes: number; sha256: string };
export async function assetChecksums(directory: string): Promise<Asset[]> {
  const files: Asset[] = [];
  async function walk(relative: string) {
    for (const entry of await readdir(join(directory, relative), {
      withFileTypes: true,
    })) {
      const path = [relative, entry.name].filter(Boolean).join('/');
      if (entry.isDirectory()) await walk(path);
      else if (entry.isFile() && path !== 'manifest.json') {
        const data = await readFile(join(directory, path));
        files.push({
          path,
          bytes: data.length,
          sha256: createHash('sha256').update(data).digest('hex'),
        });
      } else if (!entry.isFile())
        throw new Error('Forbidden map resource link or type.');
    }
  }
  await walk('');
  return files.sort((a, b) => a.path.localeCompare(b.path));
}
export function assertLocalStyle(
  style: {
    glyphs?: string;
    sprite?: string;
    sources: Record<string, { url?: string }>;
    layers: Array<Record<string, unknown>>;
  },
  vectorLayers: string[],
) {
  const prefix = '/cartes/dijon-20260930-v2/';
  if (!style.glyphs?.startsWith(prefix) || !style.sprite?.startsWith(prefix))
    throw new Error('Glyphs and sprites must be local.');
  const source = style.sources.protomaps;
  if (
    Object.keys(style.sources).length !== 1 ||
    source?.url !== `pmtiles://${prefix}dijon.pmtiles`
  )
    throw new Error('Unexpected map source.');
  for (const layer of style.layers)
    if (
      layer['source-layer'] &&
      !vectorLayers.includes(String(layer['source-layer']))
    )
      throw new Error('Layer missing from the archive.');
}

export async function verifyMapAssets(directory = root, refresh = false) {
  const manifestPath = join(directory, 'manifest.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as {
    files: Asset[];
    vectorLayers: string[];
    extract: { bounds: number[]; minZoom: number; maxZoom: number };
  };
  const style = JSON.parse(
    await readFile(join(directory, 'style.json'), 'utf8'),
  );
  assertLocalStyle(style, manifest.vectorLayers);
  const archive = await readFile(join(directory, 'dijon.pmtiles'));
  const header = bytesToHeader(
    Uint8Array.from(archive.subarray(0, 127)).buffer,
  );
  const metadataBytes = archive.subarray(
    header.jsonMetadataOffset,
    header.jsonMetadataOffset + header.jsonMetadataLength,
  );
  const metadata = JSON.parse(
    (header.internalCompression === 2
      ? gunzipSync(metadataBytes)
      : metadataBytes
    ).toString('utf8'),
  ) as { vector_layers: Array<{ id: string }> };
  const actualLayers = metadata.vector_layers.map((layer) => layer.id).sort();
  if (
    JSON.stringify(actualLayers) !==
    JSON.stringify([...manifest.vectorLayers].sort())
  )
    throw new Error('PMTiles layers differ from the manifest.');
  if (
    header.specVersion !== 3 ||
    header.tileType !== 1 ||
    header.tileCompression !== 2 ||
    !header.clustered ||
    header.minZoom !== manifest.extract.minZoom ||
    header.maxZoom !== manifest.extract.maxZoom ||
    JSON.stringify([
      header.minLon,
      header.minLat,
      header.maxLon,
      header.maxLat,
    ]) !== JSON.stringify(manifest.extract.bounds)
  )
    throw new Error('PMTiles header does not match the manifest.');
  const actual = await assetChecksums(directory);
  if (refresh) {
    manifest.files = actual;
    const { format, resolveConfig } = await import('prettier');
    await writeFile(
      manifestPath,
      await format(JSON.stringify(manifest), {
        ...(await resolveConfig(manifestPath)),
        plugins: [],
        filepath: manifestPath,
      }),
    );
  } else if (JSON.stringify(manifest.files) !== JSON.stringify(actual))
    throw new Error(
      'Map asset hashes differ. Refresh the manifest only after an intended change.',
    );
  return {
    files: actual.length,
    bytes: actual.reduce((sum, file) => sum + file.bytes, 0),
    archiveBytes: archive.length,
  };
}
if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const args = process.argv.slice(2);
  if (
    args.length > 1 ||
    (args.length === 1 && args[0] !== '--refresh-manifest')
  )
    throw new Error('Usage: node scripts/map-assets.ts [--refresh-manifest]');
  console.log(
    'Map assets:',
    await verifyMapAssets(root, args[0] === '--refresh-manifest'),
  );
}
