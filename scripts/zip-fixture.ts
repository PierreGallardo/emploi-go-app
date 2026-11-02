import { crc32 } from 'node:zlib';

/** A stored zip archive built in memory, for the tests of the scripts that read open-data files. */
export function zipOf(files: Record<string, string>): Buffer {
  const locals: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const [name, text] of Object.entries(files)) {
    const nameBytes = Buffer.from(name);
    const data = Buffer.from(text);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt32LE(crc32(data), 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt32LE(crc32(data), 16);
    entry.writeUInt32LE(data.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(nameBytes.length, 28);
    entry.writeUInt32LE(offset, 42);
    locals.push(local, nameBytes, data);
    central.push(entry, nameBytes);
    offset += local.length + nameBytes.length + data.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(files).length, 8);
  end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

/** A minimal workbook: one sheet whose rows are `[outline level, cells by column]`. */
export function workbookOf(
  sheetName: string,
  rows: [number, Record<string, string>][],
): Buffer {
  const strings: string[] = [];
  const index = (text: string) => {
    const known = strings.indexOf(text);
    return known >= 0 ? known : strings.push(text) - 1;
  };
  const sheetRows = rows
    .map(([outline, cells], position) => {
      const body = Object.entries(cells)
        .map(
          ([column, text]) =>
            `<c r="${column}${position + 1}" t="s"><v>${index(text)}</v></c>`,
        )
        .join('');
      const level = outline ? ` outlineLevel="${outline}"` : '';
      return `<row r="${position + 1}"${level}>${body}</row>`;
    })
    .join('');
  return zipOf({
    'xl/workbook.xml': `<workbook><sheets><sheet name="Autre" r:id="rId1" sheetId="1"/><sheet name="${sheetName}" r:id="rId2" sheetId="2"/></sheets></workbook>`,
    'xl/_rels/workbook.xml.rels':
      '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>',
    'xl/worksheets/sheet1.xml': '<worksheet><sheetData/></worksheet>',
    'xl/worksheets/sheet2.xml': `<worksheet><sheetData>${sheetRows}</sheetData></worksheet>`,
    'xl/sharedStrings.xml': `<sst>${strings.map((text) => `<si><t>${text}</t></si>`).join('')}</sst>`,
  });
}
