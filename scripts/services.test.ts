import assert from 'node:assert/strict';
import test from 'node:test';
import { choosePort, composeProject, deletedCopies } from './services.ts';

test('la copie principale garde ses conteneurs, chaque worktree a les siens', () => {
  assert.equal(composeProject('/work/emploi-go-app', false), 'emploi-go-local');
  assert.equal(
    composeProject(
      '/work/emploi-go-app/.claude/worktrees/Carte_Cadrage 58f',
      true,
    ),
    'emploi-go-carte-cadrage-58f',
  );
});

test('PostgreSQL et Redis gardent leur port, sinon le port par défaut libre, sinon un port libre', async () => {
  const free = async () => true;
  const busy = async () => false;
  const pick = async () => 55_432;
  assert.equal(
    await choosePort(undefined, 15_432, 5432, 'DB_PORT', busy, pick),
    15_432,
  );
  assert.equal(
    await choosePort(undefined, undefined, 5432, 'DB_PORT', free, pick),
    5432,
  );
  assert.equal(
    await choosePort(undefined, undefined, 5432, 'DB_PORT', busy, pick),
    55_432,
  );
});

test('un port choisi dans backend/.env est gardé, et refusé s’il est pris par autre chose', async () => {
  const pick = async () => assert.fail('no port should be picked');
  assert.equal(
    await choosePort('6380', 6380, 6379, 'REDIS_PORT', async () => false, pick),
    6380,
  );
  await assert.rejects(
    choosePort('6380', undefined, 6379, 'REDIS_PORT', async () => false, pick),
    /Port 6380 is in use \(REDIS_PORT in backend\/\.env\)/,
  );
});

test('seules les bases des copies supprimées de ce dépôt sont désignées pour être retirées', () => {
  assert.deepEqual(
    deletedCopies(
      [
        'emploi-go-local',
        'emploi-go-carte-58f',
        'emploi-go-supprimee-1a2',
        'emploi-go-supprimee-1a2',
        'blog',
        '',
      ],
      [
        '/work/emploi-go-app',
        '/work/emploi-go-app/.claude/worktrees/carte-58f',
      ],
    ),
    ['emploi-go-supprimee-1a2'],
  );
});
