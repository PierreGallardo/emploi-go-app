import { useDebouncedValue } from '@/lib/use-debounced-value';
import { suggestionDelay, suggestionMinChars } from './suggestions';

/** What a field asks suggestions for: the text once the typing pauses, or nothing while it is too short. */
export function useSuggestionText(value: string) {
  const text = useDebouncedValue(value.trim(), suggestionDelay);
  return text.length >= suggestionMinChars ? text : null;
}
