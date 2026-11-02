import { expect, it } from 'vitest';
import contract from '../../../../contracts/openapi.json';
import { maxGroupSize, pageSize, transitMaxMeters } from './schemas';
import { maxJobLength, maxTextLength } from './search-params';

// The API states its limits in the contract; the map copies them, and this check keeps them equal.
const parameters = Object.fromEntries(
  contract.paths['/api/establishments'].get.parameters.map(
    ({ name, schema, description }) => [name, { schema, description }],
  ),
) as Record<
  string,
  {
    schema: { maxLength?: number; maxItems?: number; maximum?: number };
    description?: string;
  }
>;

it.each([
  {
    cas: 'le texte cherché',
    valeur: maxTextLength,
    attendu: parameters.q!.schema.maxLength,
  },
  {
    cas: 'le métier',
    valeur: maxJobLength,
    attendu: parameters.job!.schema.maxLength,
  },
  {
    cas: 'un regroupement de points',
    valeur: maxGroupSize,
    attendu: parameters.siret!.schema.maxItems,
  },
])('reprend la limite de l’API pour $cas', ({ valeur, attendu }) => {
  expect(valeur).toBe(attendu);
});

it('ne demande pas une page plus grande que l’API ne l’accepte', () => {
  expect(pageSize).toBeLessThanOrEqual(parameters.pageSize!.schema.maximum!);
});

it('annonce la distance de l’arrêt que l’API retient pour les transports', () => {
  expect(parameters.transit!.description).toContain(`à ${transitMaxMeters} m`);
});
