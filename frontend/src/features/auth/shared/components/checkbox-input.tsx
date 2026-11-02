import type { ComponentProps } from 'react';
import { Check } from 'lucide-react';
import { cn } from 'cn';

/** A native box drawn in CSS: its own border can turn red, which the browser's box cannot. */
export function CheckboxInput({
  className,
  ...input
}: Omit<ComponentProps<'input'>, 'type'>) {
  return (
    <span className={cn('relative inline-flex size-5 shrink-0', className)}>
      <input
        type="checkbox"
        className="peer size-5 appearance-none rounded-[5px] border border-muted-foreground/70 bg-background transition-[border-color,background-color] checked:border-primary checked:bg-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-invalid:border-2 aria-invalid:border-destructive"
        {...input}
      />
      <Check
        aria-hidden="true"
        strokeWidth={3}
        className="pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100"
      />
    </span>
  );
}
