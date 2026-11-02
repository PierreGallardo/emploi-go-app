import { useEffect, useRef, useState, type PointerEvent } from 'react';
import { Info } from 'lucide-react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { passwordHint } from '../../password-rule';

/** The rule behind an icon: it opens on hover, keyboard focus and tap, stays while hovered and closes with Escape (WCAG 1.4.13). */
export function PasswordRuleHint() {
  const [open, setOpen] = useState(false);
  const hovered = useRef(false);
  const closing = useRef<number>(undefined);
  useEffect(() => () => window.clearTimeout(closing.current), []);
  const hover = (event: PointerEvent, inside: boolean) => {
    if (event.pointerType !== 'mouse') return;
    window.clearTimeout(closing.current);
    hovered.current = inside;
    if (inside) setOpen(true);
    // A short delay lets the pointer cross the gap between the icon and the bubble.
    else closing.current = window.setTimeout(() => setOpen(false), 150);
  };
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        type="button"
        aria-label="Règle du mot de passe"
        className="relative inline-flex size-6 items-center justify-center rounded-full text-muted-foreground after:absolute after:-inset-2.5 hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
        onPointerEnter={(event) => hover(event, true)}
        onPointerLeave={(event) => hover(event, false)}
        onFocus={(event) => {
          if (event.currentTarget.matches(':focus-visible')) setOpen(true);
        }}
        onClick={(event) => {
          // A click under a hovering mouse would close the bubble that the hover just opened.
          if (hovered.current) event.preventDefault();
        }}
      >
        <Info aria-hidden="true" className="size-4" />
      </PopoverTrigger>
      <PopoverContent
        side="top"
        align="start"
        aria-label="Règle du mot de passe"
        className="w-64 px-3 py-2 text-sm"
        onOpenAutoFocus={(event) => event.preventDefault()}
        // The focus never left the icon; sending it back would reopen the bubble that Escape closed.
        onCloseAutoFocus={(event) => event.preventDefault()}
        onPointerEnter={(event) => hover(event, true)}
        onPointerLeave={(event) => hover(event, false)}
      >
        {passwordHint}
      </PopoverContent>
    </Popover>
  );
}
