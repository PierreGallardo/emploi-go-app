import { useEffect, useRef, type ReactNode } from 'react';
import { cn } from 'cn';
import { PageTitle } from '@/components/page-title';
import { focusWasLost, markScreenShown } from '@/lib/lost-focus';

export function AuthCard({
  title,
  description,
  children,
  focusTitle = false,
  placeholder = false,
  step,
}: {
  title: string;
  description?: ReactNode;
  /** The step of the registration, out of three. */
  step?: Step;
  children: ReactNode;
  /** Moves the focus to the title when the card appears, even if nothing lost it: an error took the place of the screen. */
  focusTitle?: boolean;
  /** Stands in for a screen that is still loading: nobody reads it, so it neither takes the focus nor counts as shown. */
  placeholder?: boolean;
}) {
  const card = useRef<HTMLElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (focusTitle) heading.current?.focus();
  }, [focusTitle]);
  // Every render: whatever changed in the card may have removed the element that held the focus, so no dependency says when.
  useEffect(() => {
    if (placeholder) return;
    if (focusWasLost()) heading.current?.focus();
    markScreenShown(card.current);
  });
  return (
    <section
      ref={card}
      data-testid="auth-card"
      className="flex w-full max-w-md flex-col gap-6"
    >
      <PageTitle>{title}</PageTitle>
      <header className="flex flex-col gap-2 text-center">
        <h1
          ref={heading}
          tabIndex={-1}
          className="text-2xl font-bold tracking-tight text-balance outline-none sm:text-3xl"
        >
          {title}
        </h1>
        {description ? (
          <p className="text-sm text-balance text-muted-foreground">
            {description}
          </p>
        ) : null}
        {step ? <StepIndicator step={step} /> : null}
      </header>
      <div className="flex flex-col gap-6">{children}</div>
    </section>
  );
}

const steps = [1, 2, 3] as const;
export type Step = (typeof steps)[number];

function StepIndicator({ step }: { step: Step }) {
  return (
    <div className="mt-3 flex gap-1.5">
      <p className="sr-only">
        Étape {step} sur {steps.length}
      </p>
      {steps.map((each) => (
        <span
          key={each}
          aria-hidden="true"
          className={cn(
            'h-1 flex-1 rounded-full',
            each <= step ? 'bg-primary' : 'bg-input',
          )}
        />
      ))}
    </div>
  );
}
