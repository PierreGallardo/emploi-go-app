import type { MouseEvent } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { CircleAlert } from 'lucide-react';
import { Field, FieldLabel } from '@/components/ui/field';
import { CheckboxInput } from './checkbox-input';

// Temporary until the pages of the terms and of the privacy policy exist (#41): the links open nothing.
const notYetPublished = (event: MouseEvent<HTMLAnchorElement>) =>
  event.preventDefault();
const linkClass =
  'underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring';

/** The box an account cannot be created without, on every form that creates one. */
export function TermsCheckbox({
  name,
  registration,
  error,
}: {
  name: string;
  registration: UseFormRegisterReturn;
  error?: string;
}) {
  const errorId = `${name}-error`;
  return (
    <Field
      orientation="horizontal"
      className="items-start"
      data-invalid={!!error}
    >
      <CheckboxInput
        id={name}
        className="mt-0.5"
        aria-invalid={!!error}
        aria-describedby={error ? errorId : undefined}
        {...registration}
      />
      <FieldLabel htmlFor={name} className="block font-normal">
        {/* Not the colour alone: the border of the box thickens and the text gets an icon (WCAG 1.4.1). */}
        {error ? (
          <CircleAlert
            aria-hidden="true"
            className="mr-1 inline size-4 align-[-0.15em]"
          />
        ) : null}
        J’accepte les{' '}
        <a href="#" onClick={notYetPublished} className={linkClass}>
          conditions d’utilisation
        </a>{' '}
        et la{' '}
        <a href="#" onClick={notYetPublished} className={linkClass}>
          politique de confidentialité
        </a>
        .
      </FieldLabel>
      {error ? (
        <p id={errorId} role="alert" className="sr-only">
          {error}
        </p>
      ) : null}
    </Field>
  );
}
