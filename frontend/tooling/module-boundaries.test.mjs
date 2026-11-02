import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath, URL } from 'node:url';
import { Linter } from 'eslint';
import tseslint from 'typescript-eslint';
import { moduleBoundariesRule } from './module-boundaries.mjs';

const linter = new Linter();
const config = [
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { parser: tseslint.parser },
    plugins: { local: { rules: { boundaries: moduleBoundariesRule } } },
    rules: { 'local/boundaries': 'error' },
  },
];

function lint(filename, code) {
  return linter.verify(code, config, {
    filename: fileURLToPath(new URL(`../src/${filename}`, import.meta.url)),
  });
}

const valid = [
  [
    'routes/home.tsx',
    "import { Status } from '@/features/example/components/example';",
  ],
  ['features/example/api.ts', "import { api } from '@/api/client';"],
  ['features/example/api.ts', "import type { paths } from '@/api/schema';"],
  ['features/example/api.ts', "import { helper } from '@/lib/helper';"],
  [
    'features/example/queries.ts',
    "import { getStatus } from './api'; import { queryOptions } from '@tanstack/react-query';",
  ],
  [
    'features/example/components/example.tsx',
    "import { statusQuery } from '../queries'; import { Button } from '@/components/ui/button';",
  ],
  [
    'features/example/hooks/use-status.ts',
    "import { helper } from '../helpers';",
  ],
  ['features/example/api.test.ts', "import { api } from '@/api/client';"],
  [
    'features/example/components/example.test.tsx',
    "import { api } from '@/api/client';",
  ],
  ['routes/home.test.tsx', "import { api } from '@/api/client';"],
  ['components/ui/button.tsx', "import { cn } from '@/lib/utils';"],
  ['api/client.ts', "import createClient from 'openapi-fetch';"],
  [
    'features/auth/session/reauthentication.ts',
    "import { onUnauthorized } from '@/api/unauthorized';",
  ],
  [
    'features/example/login/components/login-screen.tsx',
    "import { loginQuery } from '../../queries'; import { Card } from '../../shared/components/card';",
  ],
  [
    'features/example/login/components/login-screen.tsx',
    "import { Field } from '@/features/example/shared/components/field';",
  ],
  [
    'features/example/shared/components/navigation.test.tsx',
    "import { LoginScreen } from '../../login/components/login-screen';",
  ],
  [
    'features/example/session/sync.ts',
    "import { sessionKey } from '../queries';",
  ],
  ['api/client.ts', "import { noteSender } from './unauthorized';"],
  [
    'features/example/api.test.ts',
    "import { onUnauthorized } from '@/api/unauthorized';",
  ],
  ['test/render.tsx', "import { onUnauthorized } from '@/api/unauthorized';"],
  ['root.tsx', "import { AppProviders } from './providers';"],
  [
    'routes/home.tsx',
    "const screen = import('@/features/example/components/example');",
  ],
  ['routes/home.tsx', "type Route = import('./+types/home').Route;"],
  ['test/handlers/map.ts', "import { http, HttpResponse } from 'msw';"],
  [
    'test/handlers/map.ts',
    "import type { EstablishmentDetail } from '@/features/map/schemas';",
  ],
  [
    'test/handlers/map.ts',
    "import type { SectorCode } from '@/features/map/sectors';",
  ],
  ['test/handlers/map.ts', "import { places } from './map/catalogue';"],
  ['test/handlers/map/rome.ts', "import { sectorsOfDivision } from './naf';"],
  ['test/handlers/index.ts', "import { mapHandlers } from './map';"],
  ['test/handlers/map.test.ts', "import { z } from 'zod';"],
  ['test/render.tsx', "import { sessionKey } from '@/features/auth/queries';"],
  ['test/network.test.ts', "import { login } from '@/features/auth/api';"],
  ['features/example/api.test.ts', "import { server } from '@/test/server';"],
  [
    'features/example/components/example.test.tsx',
    "import { renderWithProviders } from '@/test/render';",
  ],
];

for (const [filename, code] of valid) {
  test(`autorise ${filename}: ${code}`, () => {
    assert.deepEqual(lint(filename, code), []);
  });
}

const invalid = [
  [
    'components/ui/button.tsx',
    "import { Status } from '@/features/example/components/example';",
  ],
  [
    'components/ui/button.tsx',
    "import { Status } from '../../features/example/components/example';",
  ],
  ['api/client.ts', "export * from '../features/example/api';"],
  ['lib/utils.ts', "export { Home } from '../routes/home';"],
  ['lib/utils.ts', "import { App } from '../root';"],
  ['lib/utils.ts', "import { App } from '@/providers';"],
  ['components/ui/button.tsx', "import { routes } from '@/routes';"],
  ['lib/utils.ts', "import { api } from '@/api/client';"],
  ['components/ui/button.tsx', "import type { paths } from '@/api/schema';"],
  ['features/example/api.ts', "import { Home } from '@/routes/home';"],
  [
    'features/example/queries.ts',
    "import { getCompanies } from '@/features/companies/api';",
  ],
  [
    'features/example/queries.ts',
    "import { getCompanies } from '@/features/companies';",
  ],
  ['features/example/queries.ts', "export * from '../companies/queries';"],
  ['routes/home.tsx', "import { api } from '../api/client';"],
  ['routes/home.tsx', "import { getStatus } from '@/features/example/api';"],
  [
    'features/example/components/example.tsx',
    "import { getStatus } from '../api';",
  ],
  [
    'features/example/components/example.tsx',
    "import { api } from '@/api/client';",
  ],
  ['features/example/helpers.ts', "import { api } from '@/api/client';"],
  [
    'features/example/login/components/login-screen.tsx',
    "import { login } from '../../api';",
  ],
  [
    'features/example/api.ts',
    "import { LoginScreen } from './login/components/login-screen';",
  ],
  [
    'features/example/login/components/login-screen.tsx',
    "import { SignupForm } from '../../signup/components/signup-form';",
  ],
  [
    'features/example/shared/components/button.tsx',
    "import { googleError } from '@/features/example/google/errors';",
  ],
  [
    'features/example/session/sync.ts',
    "import { Account } from '../account/components/account';",
  ],
  [
    'features/example/queries.ts',
    "import { onUnauthorized } from '@/api/unauthorized';",
  ],
  [
    'features/auth/queries.ts',
    "import { onUnauthorized } from '@/api/unauthorized';",
  ],
  ['providers.tsx', "import { onUnauthorized } from '@/api/unauthorized';"],
  [
    'features/example/components/example.tsx',
    "import { onUnauthorized } from '@/api/unauthorized';",
  ],
  [
    'features/example/api.ts',
    "import { Button } from '@/components/ui/button';",
  ],
  ['features/example/api.ts', "import { statusQuery } from './queries';"],
  [
    'features/example/api.ts',
    "import { queryOptions } from '@tanstack/react-query';",
  ],
  ['features/example/api.ts', "import { useState } from 'react';"],
  ['features/example/api.ts', "import { jsx } from 'react/jsx-runtime';"],
  ['features/example/api.ts', "import { createRoot } from 'react-dom/client';"],
  [
    'features/example/queries.ts',
    "import { Status } from './components/example';",
  ],
  [
    'features/example/queries.ts',
    "import { Button } from '@/components/ui/button';",
  ],
  ['features/example/api.ts', "import createClient from 'openapi-fetch';"],
  [
    'components/ui/button.tsx',
    "const screen = import('@/features/example/components/example');",
  ],
  [
    'lib/utils.ts',
    'const screen = import(`../features/example/components/example`);',
  ],
  ['routes/home.tsx', "const client = import('@/api/client');"],
  [
    'features/example/queries.ts',
    "const other = import('@/features/companies/queries');",
  ],
  ['lib/utils.ts', "type Api = import('@/api/schema').paths;"],
  ['routes/home.test.tsx', "export * from '@/api/client';"],
  ['lib/utils.test.ts', "import { api } from '@/api/client';"],
  [
    'routes/home.tsx',
    "import { getStatus } from '../features/example/api.ts';",
  ],
  ['routes/home.tsx', "import { api } from '../api/./client.js?raw';"],
  [
    'test/handlers/map.ts',
    "import { searchEstablishments } from '@/features/map/api';",
  ],
  ['test/handlers/map.ts', "import { helper } from '@/features/map/helpers';"],
  ['test/handlers/map/extra.ts', "import { z } from 'zod';"],
  [
    'test/handlers/map/rome.ts',
    "import { sectorStyle } from '@/features/other/sectors';",
  ],
  [
    'test/handlers/index.ts',
    "import { searchEstablishments } from '@/features/map/api';",
  ],
  [
    'test/handlers/map.ts',
    "import { searchQueryOptions } from '@/features/map/queries';",
  ],
  [
    'test/handlers/map.ts',
    "import { MapScreen } from '@/features/map/components/map-screen';",
  ],
  [
    'test/handlers/map.ts',
    "import type { Session } from '@/features/auth/model';",
  ],
  ['test/handlers/map.ts', "import { z } from 'zod';"],
  ['test/handlers/map.ts', "import { api } from '@/api/client';"],
  [
    'features/example/components/example.tsx',
    "import { server } from '@/test/server';",
  ],
  ['features/example/queries.ts', "import { server } from '@/test/server';"],
  ['routes/home.tsx', "import { renderWithProviders } from '@/test/render';"],
  ['lib/utils.ts', "import { server } from '../test/server';"],
];

for (const [filename, code] of invalid) {
  test(`refuse ${filename}: ${code}`, () => {
    const messages = lint(filename, code);
    assert.equal(messages.length, 1, JSON.stringify(messages));
    assert.equal(messages[0].ruleId, 'local/boundaries');
    assert.equal(messages[0].messageId, 'boundary');
  });
}
