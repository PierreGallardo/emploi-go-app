import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import hooks from 'eslint-plugin-react-hooks';
import prettier from 'eslint-config-prettier';
import { moduleBoundariesRule } from './tooling/module-boundaries.mjs';
import { routeTitleRestriction } from './tooling/route-title.mjs';

export default tseslint.config(
  {
    ignores: [
      'build/**',
      '.react-router/**',
      'src/api/schema.d.ts',
      'playwright-report/**',
      'test-results/**',
    ],
  },
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: {
      'react-hooks': hooks,
      local: { rules: { 'module-boundaries': moduleBoundariesRule } },
    },
    rules: {
      ...hooks.configs.recommended.rules,
      'local/module-boundaries': 'error',
    },
  },
  {
    files: ['src/routes/**/*.{ts,tsx}'],
    rules: { 'no-restricted-syntax': ['error', routeTitleRestriction] },
  },
  prettier,
);
