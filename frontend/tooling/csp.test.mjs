import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  contentSecurityPolicy,
  inlineScriptHashes,
  writeCaddyPolicy,
} from './csp.ts';

const hash = (text) =>
  `'sha256-${createHash('sha256').update(text).digest('base64')}'`;

const scriptSource = (policy) =>
  policy.split('; ').find((directive) => directive.startsWith('script-src'));

test('hache chaque script inline, module compris, et ignore les scripts externes', () => {
  const html =
    '<head><script src="/assets/theme-init.js"></script></head>' +
    '<body><script>window.a = 1;</script>' +
    '<script type="module" async="">import "/assets/x.js";\n</script></body>';

  assert.deepEqual(inlineScriptHashes(html), [
    hash('window.a = 1;'),
    hash('import "/assets/x.js";\n'),
  ]);
});

test('autorise les scripts inline de toutes les pages, une seule fois chacun', () => {
  const policy = contentSecurityPolicy([
    '<script>commun</script><script>repli</script>',
    '<script>commun</script><script>prérendu</script>',
  ]);

  assert.equal(
    scriptSource(policy),
    [
      "script-src 'self'",
      ...[hash('commun'), hash('prérendu'), hash('repli')].sort(),
    ].join(' '),
  );
});

test('n’autorise aucun script inline par mot-clé', () => {
  const policy = contentSecurityPolicy(['<script>a</script>']);

  assert.doesNotMatch(
    scriptSource(policy),
    /'unsafe-inline'|'unsafe-eval'|'strict-dynamic'/,
  );
});

for (const directive of [
  "object-src 'none'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
])
  test(`garde ${directive}`, () => {
    assert.ok(contentSecurityPolicy([]).split('; ').includes(directive));
  });

test('écrit la ligne de header des pages HTML du build, sous-dossiers compris', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'csp-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const client = join(directory, 'client');
  await mkdir(join(client, 'connexion'), { recursive: true });
  await writeFile(join(client, 'index.html'), '<script>repli</script>');
  await writeFile(
    join(client, 'connexion', 'index.html'),
    '<script>page</script>',
  );
  await writeFile(join(client, 'notes.txt'), '<script>ignoré</script>');
  const file = join(directory, 'csp.caddyfile');

  await writeCaddyPolicy(client, file);

  const header = (await readFile(file, 'utf8')).split('\n')[1];
  assert.match(
    header,
    /^Content-Security-Policy "default-src 'self'; script-src 'self' /,
  );
  assert.ok(header.includes(hash('repli')));
  assert.ok(header.includes(hash('page')));
  assert.ok(!header.includes(hash('ignoré')));
});

test('refuse un build sans page HTML', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'csp-'));
  t.after(() => rm(directory, { recursive: true, force: true }));

  const file = join(directory, 'csp.caddyfile');

  await assert.rejects(writeCaddyPolicy(directory, file), /No HTML page/);
  await assert.rejects(readFile(file), { code: 'ENOENT' });
});
