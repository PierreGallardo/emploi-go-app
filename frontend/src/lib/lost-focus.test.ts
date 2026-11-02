import { renderHook } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { focusWasLost, markScreenShown, useFocusTracking } from './lost-focus';

let stopTracking: () => void;

beforeEach(() => {
  stopTracking = renderHook(() => useFocusTracking()).unmount;
});
afterEach(() => {
  stopTracking();
  document.body.replaceChildren();
});

function add<Tag extends keyof HTMLElementTagNameMap>(tag: Tag) {
  const element = document.createElement(tag);
  document.body.append(element);
  return element;
}

it('ne voit aucun focus perdu quand rien n’a été suivi', () => {
  add('button');

  expect(focusWasLost()).toBe(false);
});

it('voit le focus perdu quand l’élément focalisé est retiré', () => {
  const button = add('button');
  button.focus();
  button.remove();

  expect(document.body).toHaveFocus();
  expect(focusWasLost()).toBe(true);
});

it('voit le focus perdu quand un élément cliqué sans recevoir le focus est retiré', async () => {
  const link = add('a');
  link.tabIndex = -1;
  link.addEventListener('mousedown', (event) => event.preventDefault());
  await userEvent.setup().click(link);
  expect(document.body).toHaveFocus();
  link.remove();

  expect(focusWasLost()).toBe(true);
});

it('ne voit pas le focus perdu quand un texte cliqué disparaît', async () => {
  const message = add('p');
  await userEvent.setup().click(message);
  message.remove();

  expect(focusWasLost()).toBe(false);
});

it('ne voit pas le focus perdu tant que l’élément focalisé est dans la page', () => {
  add('button').focus();

  expect(focusWasLost()).toBe(false);
});

it('ne voit pas le focus perdu quand il est sur un autre élément de la page', () => {
  const removed = add('button');
  const kept = add('button');
  removed.focus();
  kept.focus();
  removed.remove();

  expect(focusWasLost()).toBe(false);
});

it('ne voit pas le focus perdu quand l’écran affiché est encore dans la page', () => {
  markScreenShown(add('main'));

  expect(focusWasLost()).toBe(false);
});

it('voit le focus perdu quand l’écran affiché est retiré, sans qu’aucun élément ait eu le focus', () => {
  const screen = add('main');
  markScreenShown(screen);
  screen.remove();

  expect(focusWasLost()).toBe(true);
});

it('garde l’écran affiché quand on n’en donne aucun', () => {
  const screen = add('main');
  markScreenShown(screen);
  markScreenShown(null);
  screen.remove();

  expect(focusWasLost()).toBe(true);
});

it('oublie tout quand le suivi s’arrête', () => {
  const button = add('button');
  button.focus();
  markScreenShown(add('main'));
  stopTracking();
  button.remove();
  document.body.replaceChildren();

  expect(focusWasLost()).toBe(false);
});
