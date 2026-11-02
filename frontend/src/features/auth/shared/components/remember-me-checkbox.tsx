import type { ComponentProps } from 'react';
import { Field, FieldLabel } from '@/components/ui/field';
import { CheckboxInput } from './checkbox-input';

export function RememberMeCheckbox({
  id,
  ...input
}: { id: string } & Omit<ComponentProps<'input'>, 'type'>) {
  return (
    <Field orientation="horizontal" className="min-h-11 items-center">
      <CheckboxInput id={id} {...input} />
      <FieldLabel htmlFor={id} className="font-normal">
        Rester connecté
      </FieldLabel>
    </Field>
  );
}
