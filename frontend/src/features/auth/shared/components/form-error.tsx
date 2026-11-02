import type { ReactNode } from 'react';
import { cn } from 'cn';

/** An error of the authentication screens: plain text in the colour of errors, announced when it appears. */
export function FormError({
  id,
  className,
  children,
}: {
  id?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div
      id={id}
      role="alert"
      className={cn(
        'flex flex-col gap-1 text-sm text-destructive [&_a]:rounded-sm [&_a]:font-medium [&_a]:underline [&_a]:underline-offset-4 [&_a]:focus-visible:outline-2 [&_a]:focus-visible:outline-offset-2',
        className,
      )}
    >
      {children}
    </div>
  );
}
