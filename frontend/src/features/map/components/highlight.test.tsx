import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { Highlight } from './highlight';

it.each([
  {
    cas: 'sans accent ni majuscule',
    text: 'École de musique',
    query: 'ecole',
    attendu: ['École'],
  },
  {
    cas: 'mot par mot',
    text: 'Boulanger pâtissier',
    query: 'boul pat',
    attendu: ['Boul', 'pât'],
  },
  { cas: 'rien d’absent', text: 'Boulanger', query: 'garage', attendu: [] },
])('met en gras ce qui est tapé, $cas', ({ text, query, attendu }) => {
  render(<Highlight text={text} query={query} />);

  expect(
    screen.queryAllByRole('strong').map((part) => part.textContent),
  ).toEqual(attendu);
});
