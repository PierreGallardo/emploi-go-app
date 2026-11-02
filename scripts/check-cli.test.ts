import assert from 'node:assert/strict';
import type { ExecFileException } from 'node:child_process';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import test from 'node:test';

const execute = promisify(execFile);

test('the contract check reaches its Docker prerequisite and reports a normal failure', async () => {
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => key.toLowerCase() !== 'path'),
  );
  // Docker is deliberately unavailable (empty PATH): runs the real CLI without services or a Docker install.
  env.PATH = '';
  await assert.rejects(
    execute(
      process.execPath,
      [fileURLToPath(new URL('./check.ts', import.meta.url)), 'contract'],
      { env, timeout: 45_000 },
    ),
    (error: unknown) => {
      assert.ok(error instanceof Error);
      const failure = error as ExecFileException & { stdout: string };
      assert.equal(failure.code, 1);
      assert.match(failure.stdout, /✗ contract[\s\S]*Docker is not installed/);
      return true;
    },
  );
});
