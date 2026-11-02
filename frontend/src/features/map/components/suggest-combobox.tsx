import { useId, useRef, useState } from 'react';
import { XIcon, type LucideIcon } from 'lucide-react';
import { cn } from 'cn';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Popover,
  PopoverAnchor,
  PopoverContent,
} from '@/components/ui/popover';
import { maxTextLength } from '../search-params';
import { suggestionMinChars } from '../suggestions';

type SuggestOption = {
  id: string;
  /** What the option shows. */
  content: React.ReactNode;
  /** What a screen reader reads for it. */
  text: string;
};
export type SuggestGroup = {
  id: string;
  /** Null for a group with no heading, such as the first row "Rechercher : …". */
  heading: React.ReactNode | null;
  options: SuggestOption[];
};

/** A text field with a list of suggestions, after the ARIA combobox pattern with a listbox popup. */
export function SuggestCombobox({
  label,
  placeholder,
  icon: Icon,
  clearLabel,
  describedBy,
  invalid,
  value,
  groups,
  onValueChange,
  onSelect,
  onClear,
}: {
  /** The accessible name of the field; not displayed. */
  label: string;
  placeholder: string;
  icon: LucideIcon;
  /** The accessible name of the button that empties the field. */
  clearLabel: string;
  /** The id of a message about what was typed. */
  describedBy?: string;
  invalid?: boolean;
  value: string;
  groups: SuggestGroup[];
  onValueChange: (value: string) => void;
  onSelect: (option: SuggestOption) => void;
  /** Called after the field was emptied, to update the search. */
  onClear: () => void;
}) {
  const id = useId();
  const listId = `${id}-liste`;
  const anchor = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [host, setHost] = useState<HTMLDivElement | null>(null);
  // The highlighted option belongs to the text it was moved over: typing starts again from none.
  const [highlight, setHighlight] = useState({ value, index: -1 });
  const options = groups.flatMap((group) => group.options);
  const open =
    expanded && value.trim().length >= suggestionMinChars && options.length > 0;
  const index = highlight.value === value ? highlight.index : -1;
  const active = open && index >= 0 ? options[index] : undefined;
  const optionId = (option: SuggestOption) => `${id}-option-${option.id}`;
  const move = (step: number) => {
    const count = options.length;
    const from = index < 0 ? (step > 0 ? -1 : 0) : index;
    setHighlight({ value, index: (from + step + count) % count });
  };
  const choose = (option: SuggestOption) => {
    setExpanded(false);
    onSelect(option);
  };
  return (
    <div ref={setHost}>
      <Label htmlFor={id} className="sr-only">
        {label}
      </Label>
      <Popover open={open}>
        <PopoverAnchor asChild>
          <div ref={anchor} className="relative">
            <Icon
              aria-hidden
              className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              id={id}
              ref={input}
              role="combobox"
              aria-autocomplete="list"
              aria-expanded={open}
              aria-controls={open ? listId : undefined}
              aria-activedescendant={active ? optionId(active) : undefined}
              aria-describedby={describedBy}
              aria-invalid={invalid || undefined}
              value={value}
              maxLength={maxTextLength}
              autoComplete="off"
              enterKeyHint="search"
              placeholder={placeholder}
              // Room for the clear button, there or not: the text never moves under it.
              className="pr-11 pl-9 placeholder:text-foreground/70"
              onChange={(event) => {
                onValueChange(event.target.value);
                setExpanded(true);
              }}
              onFocus={() => setExpanded(true)}
              onBlur={() => setExpanded(false)}
              onKeyDown={(event) => {
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                  if (options.length === 0) return;
                  event.preventDefault();
                  if (!open) setExpanded(true);
                  else move(event.key === 'ArrowDown' ? 1 : -1);
                } else if (
                  (event.key === 'Home' || event.key === 'End') &&
                  active
                ) {
                  // Without a highlighted option, they move the cursor in the text as usual.
                  event.preventDefault();
                  setHighlight({
                    value,
                    index: event.key === 'Home' ? 0 : options.length - 1,
                  });
                } else if (event.key === 'Escape' && open) {
                  event.preventDefault();
                  event.stopPropagation();
                  setExpanded(false);
                } else if (event.key === 'Enter' && active) {
                  // Without a highlighted option, Enter submits the form with what was typed.
                  event.preventDefault();
                  choose(active);
                }
              }}
            />
            {value && (
              <button
                type="button"
                aria-label={clearLabel}
                onClick={() => {
                  setExpanded(false);
                  onValueChange('');
                  onClear();
                  input.current?.focus();
                }}
                className="absolute top-0 right-0 grid size-11 place-items-center rounded-full text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
              >
                <XIcon aria-hidden className="size-4" />
              </button>
            )}
          </div>
        </PopoverAnchor>
        {/* The listbox is the popup of the combobox: no nameless dialog around it, and it stays in the landmark of the field. */}
        <PopoverContent
          role="presentation"
          container={host}
          align="start"
          collisionPadding={8}
          className="max-h-[min(20rem,var(--radix-popover-content-available-height))] w-(--radix-popover-trigger-width) overflow-y-auto overscroll-contain rounded-surface p-1"
          onOpenAutoFocus={(event) => event.preventDefault()}
          onCloseAutoFocus={(event) => event.preventDefault()}
          onInteractOutside={(event) => {
            if (anchor.current?.contains(event.target as Node))
              event.preventDefault();
          }}
          onEscapeKeyDown={() => setExpanded(false)}
        >
          <ul id={listId} role="listbox" aria-label={label}>
            {groups.map((group) => (
              <li key={group.id} role="presentation">
                {group.heading && (
                  <div
                    id={`${id}-groupe-${group.id}`}
                    className="flex items-center gap-1.5 px-3 pt-2.5 pb-1 text-xs font-semibold text-muted-foreground"
                  >
                    {group.heading}
                  </div>
                )}
                <ul
                  role="group"
                  aria-labelledby={
                    group.heading ? `${id}-groupe-${group.id}` : undefined
                  }
                  aria-label={group.heading ? undefined : label}
                >
                  {group.options.map((option) => (
                    <li
                      key={option.id}
                      id={optionId(option)}
                      role="option"
                      aria-selected={option === active}
                      aria-label={option.text}
                      ref={(element) => {
                        if (option === active)
                          element?.scrollIntoView?.({ block: 'nearest' });
                      }}
                      // The field keeps the focus: a press must not take it.
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => choose(option)}
                      className={cn(
                        'flex min-h-10 cursor-pointer items-center gap-2 rounded-item px-3 py-2 text-sm',
                        option === active
                          ? 'bg-accent text-accent-foreground'
                          : 'hover:bg-accent/60',
                      )}
                    >
                      {option.content}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </PopoverContent>
      </Popover>
      <div role="status" className="sr-only">
        {open
          ? `${options.length} suggestion${options.length > 1 ? 's' : ''}, utilise les flèches pour les parcourir.`
          : ''}
      </div>
    </div>
  );
}
