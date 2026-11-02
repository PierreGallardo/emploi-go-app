import { renderHook, act } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import initSource from './theme-init.js?raw';
import { setTheme, themeKey, useTheme, type ThemeChoice } from './theme';

const root = document.documentElement;
const runInit = () => new Function(initSource)();

it('« Système » efface le choix et rend la main à la préférence du système', () => {
  setTheme('light');

  setTheme('system');

  expect(root.dataset.theme).toBeUndefined();
  expect(localStorage.getItem(themeKey)).toBeNull();
});

it('un stockage bloqué n’empêche pas d’appliquer le choix à la page', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('Bloqué', 'SecurityError');
  });

  setTheme('dark');

  expect(root.dataset.theme).toBe('dark');
});

it.each<{
  change: string;
  initial: ThemeChoice;
  key: string | null;
  newValue: string | null;
  expected: ThemeChoice;
}>([
  {
    change: 'un choix',
    initial: 'light',
    key: themeKey,
    newValue: 'dark',
    expected: 'dark',
  },
  {
    change: 'son effacement',
    initial: 'dark',
    key: themeKey,
    newValue: null,
    expected: 'system',
  },
  {
    change: 'un stockage vidé',
    initial: 'dark',
    key: null,
    newValue: null,
    expected: 'system',
  },
])(
  '$change fait dans un autre onglet s’applique ici',
  ({ initial, key, newValue, expected }) => {
    setTheme(initial);
    const { result } = renderHook(() => useTheme());

    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key, newValue }));
    });

    expect(result.current).toBe(expected);
  },
);

it.each([
  { stored: 'dark', expected: 'dark' },
  { stored: 'violet', expected: undefined },
])(
  'le script du <head> lit « $stored » et pose data-theme=$expected',
  ({ stored, expected }) => {
    localStorage.setItem(themeKey, stored);

    runInit();

    expect(root.dataset.theme).toBe(expected);
  },
);

it('le script du <head> laisse la préférence du système quand le stockage est bloqué', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('Bloqué', 'SecurityError');
  });

  expect(runInit).not.toThrow();
  expect(root.dataset.theme).toBeUndefined();
});
