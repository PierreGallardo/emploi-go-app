import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);

// Only the Postgres container of this run is reachable: never the development database.
export async function isolatedDatabase() {
  const container = process.env.E2E_POSTGRES_CONTAINER;
  if (!container || !/^emploi-go-isolated-pg-[a-f0-9-]{36}$/.test(container))
    throw new Error('Lancer via npm run test:e2e dans l’environnement isolé.');
  const options = { windowsHide: true, timeout: 20_000 };
  const { stdout } = await execute(
    'docker',
    [
      'inspect',
      '--format',
      '{{index .Config.Labels "fr.emploigo.purpose"}}',
      container,
    ],
    options,
  );
  if (stdout.trim() !== 'contract-check')
    throw new Error('Le conteneur ne fait pas partie de l’environnement E2E.');
  return {
    sql: async (statement: string) =>
      (
        await execute(
          'docker',
          [
            'exec',
            container,
            'psql',
            '-U',
            'emploigo',
            '-d',
            'emploigo',
            '-v',
            'ON_ERROR_STOP=1',
            '-Atc',
            statement,
          ],
          options,
        )
      ).stdout,
  };
}
