import {
  useSyncExternalStore,
  type ComponentProps,
  type ReactNode,
} from 'react';
import { Link } from 'react-router';
import { cn } from 'cn';
import { FieldGroup, FieldSeparator } from '@/components/ui/field';

const subscribeToNothing = () => () => {};

export function AuthForm({
  className,
  children,
  ...props
}: ComponentProps<'form'>) {
  // In a prerendered page, the browser itself would send the form before React takes over, the typed email in the URL of a GET.
  const hydrated = useSyncExternalStore(
    subscribeToNothing,
    () => true,
    () => false,
  );
  return (
    <form className={cn('flex flex-col gap-6', className)} {...props}>
      <fieldset disabled={!hydrated} className="contents">
        {children}
      </fieldset>
    </form>
  );
}

export function AuthFields({ children }: { children: ReactNode }) {
  return <FieldGroup className="gap-4">{children}</FieldGroup>;
}

export function OrSeparator() {
  return <FieldSeparator className="my-0">ou</FieldSeparator>;
}

/** The links that leave the screen, always last and stacked. */
export function AuthLinks({ children }: { children: ReactNode }) {
  return (
    <div className="mt-2 flex flex-col items-center gap-6 text-center text-sm">
      {children}
    </div>
  );
}

/** The hit area reaches 44 px around the text without spacing the lines out. */
export const authLinkClass =
  'relative rounded-sm underline underline-offset-4 after:absolute after:-inset-x-2 after:-inset-y-3 focus-visible:outline-2 focus-visible:outline-offset-2';

export function AuthLink({ className, ...props }: ComponentProps<typeof Link>) {
  return <Link className={cn(authLinkClass, className)} {...props} />;
}
