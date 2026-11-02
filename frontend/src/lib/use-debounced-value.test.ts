import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useDebouncedValue } from './use-debounced-value';

beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }));
afterEach(() => vi.useRealTimers());

it('garde l’ancienne valeur jusqu’à la fin du délai', () => {
  const { result, rerender } = renderHook(
    ({ value }) => useDebouncedValue(value, 150),
    { initialProps: { value: 'a' } },
  );

  rerender({ value: 'ab' });
  act(() => vi.advanceTimersByTime(149));
  expect(result.current).toBe('a');

  act(() => vi.advanceTimersByTime(1));
  expect(result.current).toBe('ab');
});

it('ne retient que la dernière valeur d’une rafale de frappes', () => {
  const { result, rerender } = renderHook(
    ({ value }) => useDebouncedValue(value, 150),
    { initialProps: { value: '' } },
  );

  rerender({ value: 'b' });
  act(() => vi.advanceTimersByTime(100));
  rerender({ value: 'bo' });
  act(() => vi.advanceTimersByTime(100));
  expect(result.current).toBe('');

  act(() => vi.advanceTimersByTime(50));
  expect(result.current).toBe('bo');
});
