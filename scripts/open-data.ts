import { mkdir, rename, rm, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { inflateRawSync } from 'node:zlib';

// Readers of the public open-data files that `reference-data.ts` turns into a migration.

/** The files of a zip archive, by name. Only the stored and deflated methods exist in the formats read here. */
export function readZip(archive: Buffer): Map<string, () => Buffer> {
  const endSignature = 0x06054b50;
  let end = archive.length - 22;
  while (end >= 0 && archive.readUInt32LE(end) !== endSignature) end -= 1;
  if (end < 0) throw new Error('Invalid zip archive.');
  const count = archive.readUInt16LE(end + 10);
  let offset = archive.readUInt32LE(end + 16);
  const entries = new Map<string, () => Buffer>();
  for (let index = 0; index < count; index += 1) {
    if (archive.readUInt32LE(offset) !== 0x02014b50)
      throw new Error('Invalid zip archive.');
    const method = archive.readUInt16LE(offset + 10);
    const size = archive.readUInt32LE(offset + 20);
    const nameLength = archive.readUInt16LE(offset + 28);
    const extraLength = archive.readUInt16LE(offset + 30);
    const commentLength = archive.readUInt16LE(offset + 32);
    const local = archive.readUInt32LE(offset + 42);
    const name = archive.toString(
      'utf8',
      offset + 46,
      offset + 46 + nameLength,
    );
    entries.set(name, () => {
      const start =
        local +
        30 +
        archive.readUInt16LE(local + 26) +
        archive.readUInt16LE(local + 28);
      const data = archive.subarray(start, start + size);
      if (method === 0) return data;
      if (method === 8) return inflateRawSync(data);
      throw new Error(`Unsupported compression method ${method}.`);
    });
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

export function requireEntry(zip: Map<string, () => Buffer>, name: string) {
  const entry = zip.get(name);
  if (!entry) throw new Error(`File ${name} missing from the archive.`);
  return entry().toString('utf8');
}

const entities: Record<string, string> = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&apos;': "'",
};
const decode = (text: string) =>
  text.replace(/&(?:amp|lt|gt|quot|apos);/g, (entity) => entities[entity]!);

export type SheetRow = { outline: number; cells: Record<string, string> };

/** The rows of one sheet of an .xlsx file, with their outline level and their cells by column letter. */
export function readXlsxSheet(
  zip: Map<string, () => Buffer>,
  sheetName: string,
): SheetRow[] {
  const workbook = requireEntry(zip, 'xl/workbook.xml');
  const sheet = [...workbook.matchAll(/<sheet\s([^>]*)\/>/g)]
    .map((match) => match[1]!)
    .find((attributes) => attributes.includes(`name="${sheetName}"`));
  const relationId = sheet?.match(/r:id="([^"]+)"/)?.[1];
  if (!relationId) throw new Error(`Sheet "${sheetName}" not found.`);
  const relations = requireEntry(zip, 'xl/_rels/workbook.xml.rels');
  const target = [...relations.matchAll(/<Relationship\s([^>]*)\/>/g)]
    .map((match) => match[1]!)
    .find((attributes) => attributes.includes(`Id="${relationId}"`))
    ?.match(/Target="([^"]+)"/)?.[1];
  if (!target) throw new Error(`Sheet "${sheetName}" not found.`);
  const strings = [
    ...requireEntry(zip, 'xl/sharedStrings.xml').matchAll(
      /<si>([\s\S]*?)<\/si>/g,
    ),
  ].map((match) => decode(match[1]!.replace(/<[^>]+>/g, '')));
  const xml = requireEntry(
    zip,
    target.startsWith('/') ? target.slice(1) : `xl/${target}`,
  );
  return [...xml.matchAll(/<row([^>]*)>([\s\S]*?)<\/row>/g)].map(
    ([, attributes, body]) => {
      const cells: Record<string, string> = {};
      for (const cell of body!.matchAll(
        /<c r="([A-Z]+)\d+"([^>]*?)(?:\/>|>(?:<v>([\s\S]*?)<\/v>)?<\/c>)/g,
      )) {
        const [, column, cellAttributes, value] = cell;
        if (value === undefined) continue;
        cells[column!] = cellAttributes!.includes('t="s"')
          ? (strings[Number(value)] ?? '')
          : decode(value);
      }
      return {
        outline: Number(attributes!.match(/outlineLevel="(\d+)"/)?.[1] ?? 0),
        cells,
      };
    },
  );
}

/** Replaces a generated file only by a complete one: written beside it, then renamed. */
export async function writeAtomically(path: string, content: string) {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${process.pid}.tmp`;
  try {
    await writeFile(temporary, content);
    await rename(temporary, path);
  } finally {
    await rm(temporary, { force: true });
  }
}

export async function download(url: string) {
  const response = await fetch(url, {
    headers: { 'User-Agent': 'Emploi Go (Mission Locale de Dijon)' },
  });
  if (!response.ok)
    throw new Error(`Download failed (${response.status}): ${url}`);
  return Buffer.from(await response.arrayBuffer());
}
