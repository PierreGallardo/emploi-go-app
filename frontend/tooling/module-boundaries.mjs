import path from 'node:path';
import { fileURLToPath, URL } from 'node:url';

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url));
const composition = /^(?:root|providers|routes)$/;
const shared = /^(?:api|components|lib)(?:\/|$)/;
const uiOrLib = /^(?:components|lib)(?:\/|$)/;
const featurePattern = /^features\/([^/]+)(?:\/(.+))?$/;
const componentModule = /(?:^|\/)components\//;

function featurePart(moduleName) {
  return moduleName?.match(/^([^/]+)\//)?.[1] ?? null;
}

function sourcePath(filename) {
  const relative = path.relative(sourceRoot, filename).replaceAll('\\', '/');
  if (relative.startsWith('../') || path.isAbsolute(relative)) return null;
  return relative.replace(/\.(?:[cm]?[jt]sx?)$/, '');
}

function literalValue(node) {
  if (typeof node?.value === 'string') return node.value;
  if (node?.type === 'TemplateLiteral' && node.expressions.length === 0) {
    return node.quasis[0].value.cooked;
  }
  return null;
}

function targetPath(specifier, filename) {
  const modulePath = specifier.split(/[?#]/, 1)[0].replaceAll('\\', '/');
  if (modulePath.startsWith('@/')) {
    return sourcePath(path.resolve(sourceRoot, modulePath.slice(2)));
  }
  if (modulePath.startsWith('.')) {
    return sourcePath(path.resolve(path.dirname(filename), modulePath));
  }
  return null;
}

export const moduleBoundariesRule = {
  meta: {
    type: 'problem',
    schema: [],
    messages: {
      boundary: '{{reason}} Import interdit : {{target}}.',
    },
  },
  create(context) {
    const filename = context.physicalFilename;
    const from = sourcePath(filename);
    if (!from) return {};
    const feature = from.match(featurePattern);
    const moduleName = feature?.[2];
    const apiModule = /^api(?:\.(?:test|spec))?$/.test(moduleName ?? '');
    const queryModule = /^queries(?:\.(?:test|spec))?$/.test(moduleName ?? '');
    const featureComponent = componentModule.test(moduleName ?? '');
    const routeModule = from.startsWith('routes/');
    const testModule = /\.(?:test|spec)$/.test(from);
    const testSupport = from.startsWith('test/');
    const handlerFeature = testModule
      ? undefined
      : from.match(/^test\/handlers\/([^/]+)/)?.[1];

    function inspect(node, source = node.source) {
      const specifier = literalValue(source);
      if (!specifier) return;
      const target = targetPath(specifier, filename);
      const targetFeature = target?.match(featurePattern);
      const report = (reason) =>
        context.report({
          node: source,
          messageId: 'boundary',
          data: { reason, target: specifier },
        });

      if (/^openapi-fetch(?:\/|$)/.test(specifier) && from !== 'api/client') {
        return report('Le transport HTTP est configuré dans api/client.ts.');
      }
      if (apiModule && specifier.startsWith('@tanstack/')) {
        return report('api.ts gère le transport ; queries.ts gère le cache.');
      }
      if (apiModule && /^(?:react|react-dom)(?:\/|$)/.test(specifier)) {
        return report('api.ts reste indépendant du framework d’interface.');
      }
      if (handlerFeature) {
        const allowed = target
          ? handlerFeature === 'index'
            ? target.startsWith('test/handlers/')
            : target.startsWith(`test/handlers/${handlerFeature}/`) ||
              (targetFeature?.[1] === handlerFeature &&
                ['schemas', 'errors', 'sectors'].includes(targetFeature[2]))
          : specifier === 'msw';
        if (!allowed) {
          return report(
            'Les réponses simulées importent msw, les fichiers de leur dossier et, pour une fonctionnalité, ses types et données (schemas, errors, sectors) seulement.',
          );
        }
      }
      if (!target) return;
      if (target.startsWith('test/') && !testSupport && !testModule) {
        return report('Le code de production n’importe pas src/test.');
      }
      const upperLayer =
        target.startsWith('routes/') || composition.test(target);
      if (shared.test(from) && (target.startsWith('features/') || upperLayer)) {
        return report(
          'Le socle commun ne dépend pas des écrans ni des fonctionnalités.',
        );
      }
      if (uiOrLib.test(from) && /^api(?:\/|$)/.test(target)) {
        return report(
          'Les composants partagés et utilitaires ne dépendent pas de l’API.',
        );
      }
      if (
        feature &&
        (upperLayer || (targetFeature && feature[1] !== targetFeature[1]))
      ) {
        return report(
          'Une fonctionnalité ne dépend pas des routes ni d’une autre fonctionnalité.',
        );
      }
      const part = featurePart(moduleName);
      const targetPart = featurePart(targetFeature?.[2]);
      if (
        feature &&
        !testModule &&
        part &&
        targetPart &&
        targetFeature[1] === feature[1] &&
        targetPart !== part &&
        targetPart !== 'shared'
      ) {
        return report(
          'Un sous-dossier de fonctionnalité dépend de la racine et de shared/, pas d’un sous-dossier voisin.',
        );
      }
      if (
        target === 'api/unauthorized' &&
        !from.startsWith('api/') &&
        from !== 'features/auth/session/reauthentication' &&
        !testModule &&
        !testSupport
      ) {
        return report(
          'La reconnexion automatique est installée par features/auth/session/reauthentication et par le client commun.',
        );
      }
      if (target === 'api/client' && !from.startsWith('api/') && !apiModule) {
        // Un test d'écran peut importer le client commun pour envoyer une lecture protégée et traverser le vrai
        // middleware du 401 (authenticated-screen.test.tsx), jamais pour l'espionner.
        const mockingImport = [
          'ImportDeclaration',
          'ImportExpression',
        ].includes(node.type);
        if (!(
          testModule &&
          (routeModule || featureComponent) &&
          mockingImport
        )) {
          return report(
            'Utiliser queries.ts ; seul api.ts appelle le client HTTP.',
          );
        }
      }
      if ((routeModule || featureComponent) && targetFeature?.[2] === 'api') {
        return report(
          'Les écrans et composants consomment queries.ts, pas api.ts.',
        );
      }
      if (
        apiModule &&
        (/^components(?:\/|$)/.test(target) ||
          componentModule.test(targetFeature?.[2] ?? '') ||
          targetFeature?.[2] === 'queries')
      ) {
        return report(
          'api.ts ne dépend pas des composants ni des configurations de cache.',
        );
      }
      if (
        queryModule &&
        (/^components(?:\/|$)/.test(target) ||
          componentModule.test(targetFeature?.[2] ?? ''))
      ) {
        return report('queries.ts ne dépend pas des composants.');
      }
    }

    return {
      ImportDeclaration: inspect,
      ExportNamedDeclaration: inspect,
      ExportAllDeclaration: inspect,
      ImportExpression: inspect,
      TSImportType(node) {
        inspect(node, node.source ?? node.argument?.literal ?? node.argument);
      },
    };
  },
};
