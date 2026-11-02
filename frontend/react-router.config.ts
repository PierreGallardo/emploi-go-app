import type { Config } from '@react-router/dev/config';
import { join } from 'node:path';
import { writeCaddyPolicy } from './tooling/csp.ts';

export default {
  appDirectory: 'src',
  ssr: false,
  prerender: ['/connexion', '/inscription', '/mot-de-passe-oublie'],
  // After the prerender: the policy allows the inline scripts of every HTML page written.
  async buildEnd({ reactRouterConfig: { buildDirectory } }) {
    await writeCaddyPolicy(
      join(buildDirectory, 'client'),
      join(buildDirectory, 'csp.caddyfile'),
    );
  },
} satisfies Config;
