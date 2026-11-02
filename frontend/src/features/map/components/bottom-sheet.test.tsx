import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { BottomSheet, sheetHeights, type SheetSnap } from './bottom-sheet';

// A search of 190 pixels above the list, as measured.
const heights = sheetHeights(800, 190);
const sheet = (snap: SheetSnap, onSnap = vi.fn()) => {
  render(
    <BottomSheet
      label="Établissements"
      heights={heights}
      snap={snap}
      onSnap={onSnap}
    >
      <p>Contenu</p>
    </BottomSheet>,
  );
  return {
    onSnap,
    region: screen.getByRole('region', { name: 'Établissements' }),
    handle: screen.getByRole('button', {
      name: 'Liste des établissements',
    }),
  };
};
beforeEach(() => {
  Element.prototype.setPointerCapture = vi.fn();
});

it('range ses trois positions du plus petit au plus grand, la dernière laissant un liseré de carte', () => {
  expect(heights[0]).toBeLessThan(heights[1]);
  expect(heights[1]).toBeLessThan(heights[2]);
  expect(heights[2]).toBeLessThan(800);
  expect(sheetHeights(0, 190)).toEqual([0, 0, 0]);
});

it('élève la position la plus basse quand la recherche occupe plus de place', () => {
  expect(sheetHeights(800, 250)[0]).toBeGreaterThan(sheetHeights(800, 190)[0]);
});

it.each([
  { snap: 'collapsed', expanded: 'false' },
  { snap: 'half', expanded: 'true' },
  { snap: 'full', expanded: 'true' },
] as const)(
  'dit sur la poignée si la liste est dépliée en position « $snap »',
  ({ snap, expanded }) => {
    const { handle } = sheet(snap);

    expect(handle).toHaveAttribute('aria-expanded', expanded);
  },
);

it.each([
  { snap: 'collapsed', index: 0 },
  { snap: 'half', index: 1 },
  { snap: 'full', index: 2 },
] as const)('prend la hauteur de sa position « $snap »', ({ snap, index }) => {
  const { region } = sheet(snap);

  expect(region).toHaveStyle({ height: `${heights[index]}px` });
});

it.each([
  { snap: 'collapsed', suivante: 'half' },
  { snap: 'half', suivante: 'full' },
  { snap: 'full', suivante: 'collapsed' },
] as const)(
  'passe de « $snap » à « $suivante » au clic sur la poignée, sans geste de glisser',
  async ({ snap, suivante }) => {
    const user = userEvent.setup();
    const { onSnap, handle } = sheet(snap);

    await user.click(handle);

    expect(onSnap).toHaveBeenCalledWith(suivante);
  },
);

it('suit le doigt pendant le glissement, puis se pose sur la position la plus proche', async () => {
  const user = userEvent.setup();
  const { onSnap, handle, region } = sheet('half');

  await user.pointer([
    { keys: '[MouseLeft>]', target: handle, coords: { clientY: 500 } },
    { target: handle, coords: { clientY: 480 } },
  ]);
  expect(region).toHaveStyle({ height: `${heights[1] + 20}px` });
  await user.pointer({
    keys: '[/MouseLeft]',
    target: handle,
    coords: { clientY: 480 },
  });

  expect(onSnap).toHaveBeenCalledWith('half');
});

it.each([
  { cas: 'vers le haut', fin: 400, attendu: 'full' },
  { cas: 'vers le bas', fin: 600, attendu: 'collapsed' },
] as const)(
  'un coup de pouce $cas passe à la position voisine',
  async ({ fin, attendu }) => {
    const user = userEvent.setup();
    const { onSnap, handle } = sheet('half');

    await user.pointer([
      { keys: '[MouseLeft>]', target: handle, coords: { clientY: 500 } },
      { target: handle, coords: { clientY: fin } },
      { keys: '[/MouseLeft]', target: handle, coords: { clientY: fin } },
    ]);

    expect(onSnap).toHaveBeenLastCalledWith(attendu);
  },
);

it('ne dépasse jamais ses positions extrêmes en glissant', async () => {
  const user = userEvent.setup();
  const { handle, region } = sheet('full');

  await user.pointer([
    { keys: '[MouseLeft>]', target: handle, coords: { clientY: 500 } },
    { target: handle, coords: { clientY: 100 } },
  ]);
  expect(region).toHaveStyle({ height: `${heights[2]}px` });
  await user.pointer({ target: handle, coords: { clientY: 5000 } });

  expect(region).toHaveStyle({ height: `${heights[0]}px` });
});

it('change de position au clavier, même après un glissement', async () => {
  const user = userEvent.setup();
  const { onSnap, handle } = sheet('half');
  await user.pointer([
    { keys: '[MouseLeft>]', target: handle, coords: { clientY: 500 } },
    { target: handle, coords: { clientY: 480 } },
    { keys: '[/MouseLeft]', target: handle, coords: { clientY: 480 } },
  ]);
  onSnap.mockClear();
  handle.focus();

  await user.keyboard('{Enter}');

  expect(onSnap).toHaveBeenCalledWith('full');
});
