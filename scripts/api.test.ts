import { errorCode } from './errors.ts';
import type { AddressInfo } from 'node:net';
import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import {
  canonicalize,
  contractPath,
  createArtifacts,
  syncApi,
  typesPath,
} from './api.ts';

test('the same API produces stable output regardless of object key order', async () => {
  const first = {
    openapi: '3.1.0',
    info: { title: 'Example', version: '1' },
    paths: {},
    components: {
      schemas: {
        Company: {
          type: 'object',
          required: ['name'],
          properties: {
            description: { type: ['string', 'null'] },
            name: { type: 'string' },
          },
        },
      },
    },
  };
  const reversed = JSON.parse(JSON.stringify(first));
  reversed.components.schemas.Company.properties = {
    name: { type: 'string' },
    description: { type: ['string', 'null'] },
  };
  assert.deepEqual(
    await createArtifacts(first),
    await createArtifacts(reversed),
  );
});

test('required and nullable API properties remain distinct in TypeScript', async () => {
  const { types } = await createArtifacts({
    openapi: '3.1.0',
    info: { title: 'Example', version: '1' },
    paths: {},
    components: {
      schemas: {
        Company: {
          type: 'object',
          required: ['name', 'description'],
          properties: {
            name: { type: 'string' },
            description: { type: ['string', 'null'] },
            website: { type: 'string' },
          },
        },
      },
    },
  });
  assert.match(types, /name: string;/);
  assert.match(types, /description: string \| null;/);
  assert.match(types, /website\?: string;/);
});

test('canonicalization preserves array order and rejects non-OpenAPI exports', async () => {
  assert.deepEqual(canonicalize({ required: ['z', 'a'] }), {
    required: ['z', 'a'],
  });
  await assert.rejects(createArtifacts({ status: 'UP' }), /OpenAPI/);
});

test('a failed export leaves both tracked files unchanged', async () => {
  const read = async (path: string) =>
    readFile(path, 'utf8').catch((error) => {
      if (errorCode(error) === 'ENOENT') return null;
      throw error;
    });
  const before = await Promise.all([read(contractPath), read(typesPath)]);
  const server = createServer((_request, response) => {
    response.writeHead(503, { 'Content-Type': 'application/json' });
    response.end('{"error":"unavailable"}');
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    await assert.rejects(
      syncApi({
        url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/v3/api-docs`,
      }),
      /HTTP 503/,
    );
    assert.deepEqual(
      await Promise.all([read(contractPath), read(typesPath)]),
      before,
    );
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }
});

test('a changed source generation prevents publishing an otherwise valid export', async () => {
  const before = await Promise.all([
    readFile(contractPath, 'utf8'),
    readFile(typesPath, 'utf8'),
  ]);
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      JSON.stringify({
        openapi: '3.1.0',
        info: { title: 'Generation guard test', version: '1' },
        paths: {},
      }),
    );
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  let guarded = false;
  try {
    await assert.rejects(
      syncApi({
        url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/v3/api-docs`,
        beforePublish: async () => {
          guarded = true;
          throw new Error('sources changed during export');
        },
      }),
      /sources changed/,
    );
    assert.equal(guarded, true);
    assert.deepEqual(
      await Promise.all([
        readFile(contractPath, 'utf8'),
        readFile(typesPath, 'utf8'),
      ]),
      before,
    );
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  }
});
