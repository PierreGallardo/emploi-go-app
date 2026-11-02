import type { Ref } from 'react';
import { REGEXP_ONLY_DIGITS } from 'input-otp';
import { Field, FieldLabel } from '@/components/ui/field';
import {
  InputOTP,
  InputOTPGroup,
  InputOTPSlot,
} from '@/components/ui/input-otp';

const length = 6;
const slots = Array.from({ length }, (_, index) => index);

/** Six boxes over one real input: pasting, one-time-code autofill and the numeric keyboard keep working. */
export function CodeInput({
  id,
  value,
  onChange,
  onComplete,
  inputRef,
  disabled,
  invalid,
  describedBy,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  onComplete: () => void;
  inputRef: Ref<HTMLInputElement>;
  disabled: boolean;
  invalid: boolean;
  /** The ids of the messages that speak of the code, shown elsewhere. */
  describedBy?: string;
}) {
  return (
    <Field data-invalid={invalid} className="items-center">
      <FieldLabel htmlFor={id} className="sr-only">
        Code à 6 chiffres reçu par email
      </FieldLabel>
      <InputOTP
        id={id}
        ref={inputRef}
        maxLength={length}
        pattern={REGEXP_ONLY_DIGITS}
        // A code copied from the email may carry spaces or a dash.
        pasteTransformer={(pasted) => pasted.replace(/[\s-]/g, '')}
        inputMode="numeric"
        autoComplete="one-time-code"
        value={value}
        onChange={onChange}
        onComplete={onComplete}
        disabled={disabled}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        containerClassName="justify-center"
      >
        <InputOTPGroup className="gap-1.5 min-[360px]:gap-2">
          {slots.map((index) => (
            <InputOTPSlot
              key={index}
              index={index}
              className="size-10 rounded-md border text-lg font-semibold first:rounded-md min-[360px]:size-12 sm:size-13"
            />
          ))}
        </InputOTPGroup>
      </InputOTP>
    </Field>
  );
}
