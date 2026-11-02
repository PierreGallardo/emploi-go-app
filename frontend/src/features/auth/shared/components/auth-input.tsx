import { Eye, EyeOff } from 'lucide-react';
import { cn } from 'cn';
import type { UseFormRegisterReturn } from 'react-hook-form';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { PasswordRuleHint } from './password-rule-hint';
import {
  missingCriteria,
  passwordCompliantMessage,
  passwordHint,
} from '../../password-rule';

interface AuthInputProps {
  name: string;
  label: string;
  type?: 'text' | 'email' | 'password';
  autoComplete: string;
  error?: string;
  registration: UseFormRegisterReturn;
  description?: string;
  /** The password being typed: the rule describes the field behind an icon, and its compliance is announced. */
  checkedPassword?: string;
  /** A password shown in clear, by the eye of this field or of the field it confirms. */
  shown?: boolean;
  /** Puts the eye in the field. */
  onToggleShown?: () => void;
  maxLength?: number;
  inputMode?: 'numeric';
  /** A refusal of the API about this field, shown elsewhere: `describedBy` is the id of its message. */
  invalid?: boolean;
  describedBy?: string;
}
export function AuthInput({
  name,
  label,
  type = 'text',
  autoComplete,
  error,
  registration,
  description,
  checkedPassword,
  shown = false,
  onToggleShown,
  maxLength,
  inputMode,
  invalid = false,
  describedBy,
}: AuthInputProps) {
  const checked = checkedPassword !== undefined;
  const input = (
    <Input
      id={name}
      type={type === 'password' && shown ? 'text' : type}
      autoComplete={autoComplete}
      autoCapitalize="none"
      spellCheck={false}
      maxLength={maxLength}
      inputMode={inputMode}
      // Edge draws its own eye in a password field, beside ours or alone in the confirmation it also drives.
      className={cn(
        type === 'password' && '[&::-ms-reveal]:hidden',
        onToggleShown && 'pr-12',
      )}
      aria-invalid={!!error || invalid}
      aria-describedby={
        [
          description ? `${name}-description` : '',
          checked ? `${name}-rule` : '',
          error ? `${name}-error` : '',
          invalid ? (describedBy ?? '') : '',
        ]
          .filter(Boolean)
          .join(' ') || undefined
      }
      {...registration}
    />
  );
  return (
    <Field data-invalid={!!error || invalid}>
      {checked ? (
        <div className="flex items-center gap-1.5">
          <FieldLabel htmlFor={name}>{label}</FieldLabel>
          <PasswordRuleHint />
          {/* The bubble is unmounted while closed: this copy keeps the rule in the description of the field. */}
          <p id={`${name}-rule`} className="sr-only">
            {passwordHint}
          </p>
        </div>
      ) : (
        <FieldLabel htmlFor={name}>{label}</FieldLabel>
      )}
      {onToggleShown ? (
        <div className="relative">
          {input}
          <button
            type="button"
            className="absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-r-md text-muted-foreground hover:text-foreground focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring"
            aria-label={
              shown ? 'Masquer le mot de passe' : 'Afficher le mot de passe'
            }
            onClick={onToggleShown}
          >
            {shown ? (
              <EyeOff aria-hidden="true" className="size-5" />
            ) : (
              <Eye aria-hidden="true" className="size-5" />
            )}
          </button>
        </div>
      ) : (
        input
      )}
      {description ? (
        <FieldDescription
          id={`${name}-description`}
          // shadcn pulls up a description that sits under its label; this one sits under the field, whatever follows it.
          className="nth-last-2:mt-0"
        >
          {description}
        </FieldDescription>
      ) : null}
      {error ? (
        <FieldError id={`${name}-error`} role="alert">
          {error}
        </FieldError>
      ) : null}
      {checkedPassword === undefined ? null : (
        // Always mounted: a region that appears with its text is not reliably announced, even when a pasted password becomes compliant at once.
        <p role="status" aria-atomic="true" className="sr-only">
          {missingCriteria(checkedPassword).length === 0
            ? passwordCompliantMessage
            : ''}
        </p>
      )}
    </Field>
  );
}
