import assert from 'node:assert/strict';
import test from 'node:test';
import { buildErrors } from './dev.ts';
import { CommandError, backendDir } from './dev-process.ts';

test('un échec de compilation n’affiche que les erreurs du compilateur', () => {
  const output = [
    '[ERROR] COMPILATION ERROR : ',
    `[ERROR] ${backendDir}/src/main/java/fr/emploigo/Foo.java:[3,5] cannot find symbol`,
    '[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.0:compile (default-compile) on project emploi-go: Compilation failure',
    `[ERROR] ${backendDir}/src/main/java/fr/emploigo/Foo.java:[3,5] cannot find symbol`,
    '[ERROR]   symbol:   variable missing',
    '[ERROR] -> [Help 1]',
    '[ERROR] ',
    '[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.',
  ].join('\n');
  assert.equal(
    buildErrors(new CommandError('backend failed (1).', output)),
    'backend/src/main/java/fr/emploigo/Foo.java:[3,5] cannot find symbol\n  symbol:   variable missing',
  );
  assert.equal(buildErrors(new Error('mvnw not found')), 'mvnw not found');
});
