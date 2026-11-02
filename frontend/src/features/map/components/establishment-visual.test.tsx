import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { EstablishmentVisual } from './establishment-visual';

// The picture is decorative (empty alt): its role is presentation.
const picture = () => screen.getByRole('presentation');

it('montre la photo de l’établissement quand il en a une', () => {
  render(
    <EstablishmentVisual
      sector="FOOD"
      imageUrl="/api/establishment-images/fictif.webp"
    />,
  );

  expect(picture()).toHaveAttribute(
    'src',
    '/api/establishment-images/fictif.webp',
  );
});

it('montre le visuel du secteur à la place d’une photo introuvable', () => {
  render(
    <EstablishmentVisual
      sector="FOOD"
      imageUrl="/api/establishment-images/fictif.webp"
    />,
  );

  // A failed image is no user action: only its event can stand for it.
  fireEvent.error(picture());

  expect(picture()).toHaveAttribute('src', '/visuels/secteurs/FOOD.webp');
});
