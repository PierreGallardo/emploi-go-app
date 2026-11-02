import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { establishmentDetails } from '@/test/handlers/map';
import { renderWithProviders } from '@/test/render';
import { sectorStyle } from '../sectors';
import type { MapHover } from '../map-engine';
import { MapTooltip } from './map-tooltip';

const show = (hover: MapHover | null, mapWidth = 1000, reservedRight = 0) =>
  renderWithProviders(
    <MapTooltip
      scope="jeune_fictif"
      hover={hover}
      mapWidth={mapWidth}
      reservedRight={reservedRight}
    />,
  );
const [first, second] = establishmentDetails;

it('montre le nom, le secteur et la commune de l’établissement sous la souris', async () => {
  show({ kind: 'point', id: first!.siret, x: 100, y: 100, radius: 9 });

  expect(await screen.findByText(first!.name)).toBeInTheDocument();
  expect(
    screen.getByText(`${sectorStyle[first!.sector].label} · ${first!.commune}`),
  ).toBeInTheDocument();
});

it('nomme les établissements d’un petit regroupement', async () => {
  show({
    kind: 'cluster',
    count: 2,
    ids: [first!.siret, second!.siret],
    x: 100,
    y: 100,
    radius: 20,
  });

  expect(await screen.findByText(first!.name)).toBeInTheDocument();
  expect(screen.getByText(second!.name)).toBeInTheDocument();
});

it('ne donne que le nombre d’un gros regroupement', () => {
  show({ kind: 'cluster', count: 12, ids: null, x: 100, y: 100, radius: 25 });

  expect(
    screen.getByText('12 entreprises · cliquer pour zoomer'),
  ).toBeInTheDocument();
});

it('n’affiche rien d’un petit regroupement avant de connaître ses établissements', () => {
  show({ kind: 'cluster', count: 3, ids: null, x: 100, y: 100, radius: 20 });

  expect(screen.queryByTestId('map-tooltip')).not.toBeInTheDocument();
});

it('reste caché des lecteurs d’écran, la liste étant leur alternative', () => {
  // Hidden from assistive technology, the tooltip has no role to query: its test id is the only handle.
  show({
    kind: 'cluster',
    count: 12,
    ids: null,
    x: 100,
    y: 100,
    radius: 25,
  });

  expect(screen.getByTestId('map-tooltip')).toHaveAttribute(
    'aria-hidden',
    'true',
  );
});

it('disparaît quand plus rien n’est survolé', () => {
  const { rerender } = show({
    kind: 'cluster',
    count: 12,
    ids: null,
    x: 100,
    y: 100,
    radius: 25,
  });

  rerender(
    <MapTooltip
      scope="jeune_fictif"
      hover={null}
      mapWidth={1000}
      reservedRight={0}
    />,
  );

  expect(screen.queryByTestId('map-tooltip')).not.toBeInTheDocument();
});

it.each([
  {
    cas: 'à droite du point quand la place ne manque pas',
    x: 100,
    left: 100 + 9 + 12,
  },
  { cas: 'à gauche du point près du panneau', x: 600, left: 600 - 9 - 12 },
])('place l’infobulle $cas', ({ x, left }) => {
  show(
    { kind: 'cluster', count: 12, ids: null, x, y: 100, radius: 9 },
    1000,
    424,
  );

  expect(screen.getByTestId('map-tooltip')).toHaveStyle({ left: `${left}px` });
});
