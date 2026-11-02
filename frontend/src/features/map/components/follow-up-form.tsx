import { useEffect } from 'react';
import { useForm, useWatch, type UseFormReturn } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Button } from '@/components/ui/button';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import { Textarea } from '@/components/ui/textarea';
import { todayIso } from '../follow-up-format';
import {
  actionTypes,
  applicationChannels,
  callDirections,
  contractTypes,
  emptyFollowUpForm,
  followUpChannels,
  followUpFormSchema,
  interviewFormats,
  noteMaxLength,
  refusalReasons,
  textMaxLength,
  toBody,
  toFormValues,
  visitResults,
  type FollowUpAction,
  type FollowUpBody,
  type FollowUpFormOutput,
  type FollowUpFormValues,
} from '../follow-up-schemas';
import { actionIcons } from './follow-up-icons';

type Options = readonly (readonly [string, string])[];
type Form = UseFormReturn<FollowUpFormValues, unknown, FollowUpFormOutput>;

function Choice({
  form,
  name,
  label,
  options,
}: {
  form: Form;
  name: keyof FollowUpFormValues;
  label: string;
  options: Options;
}) {
  const id = `follow-up-${name}`;
  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <NativeSelect id={id} className="w-full" {...form.register(name)}>
        <option value="">Non précisé</option>
        {options.map(([code, text]) => (
          <option key={code} value={code}>
            {text}
          </option>
        ))}
      </NativeSelect>
    </Field>
  );
}

function Text({
  form,
  name,
  label,
}: {
  form: Form;
  name: 'position' | 'label';
  label: string;
}) {
  const id = `follow-up-${name}`;
  const error = form.formState.errors[name]?.message;
  return (
    <Field data-invalid={!!error}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input
        id={id}
        maxLength={textMaxLength * 2}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        {...form.register(name)}
      />
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  );
}

function DateInput({
  form,
  name,
  label,
}: {
  form: Form;
  name: 'date' | 'startDate';
  label: string;
}) {
  const id = `follow-up-${name}`;
  const error = form.formState.errors[name]?.message;
  return (
    <Field data-invalid={!!error} className="min-w-0 flex-1">
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input
        id={id}
        type="date"
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        {...form.register(name)}
      />
      {error && <FieldError id={`${id}-error`}>{error}</FieldError>}
    </Field>
  );
}

/** The details that are worth asking for the type chosen: all optional, and never a name or a contact. */
function Details({
  form,
  type,
}: {
  form: Form;
  type: FollowUpFormValues['type'];
}) {
  switch (type) {
    case 'APPLICATION_SENT':
      return (
        <>
          <Choice
            form={form}
            name="channel"
            label="Comment ?"
            options={applicationChannels}
          />
          <Text form={form} name="position" label="Poste visé" />
        </>
      );
    case 'FOLLOW_UP':
      return (
        <Choice
          form={form}
          name="channel"
          label="Comment ?"
          options={followUpChannels}
        />
      );
    case 'PHONE_CALL':
      return (
        <Choice
          form={form}
          name="direction"
          label="Qui a appelé ?"
          options={callDirections}
        />
      );
    case 'ON_SITE_VISIT':
      return (
        <Choice
          form={form}
          name="result"
          label="Résultat"
          options={visitResults}
        />
      );
    case 'INTERVIEW':
      return (
        <>
          <Choice
            form={form}
            name="format"
            label="Format"
            options={interviewFormats}
          />
          <Text form={form} name="position" label="Poste visé" />
        </>
      );
    case 'POSITIVE_ANSWER':
      return (
        <>
          <Choice
            form={form}
            name="contractType"
            label="Type de contrat"
            options={contractTypes}
          />
          <DateInput form={form} name="startDate" label="Date de début" />
        </>
      );
    case 'NEGATIVE_ANSWER':
      return (
        <Choice
          form={form}
          name="reason"
          label="Raison"
          options={refusalReasons}
        />
      );
    case 'OTHER':
      return <Text form={form} name="label" label="De quoi s’agit-il ?" />;
    default:
      return null;
  }
}

/** One action of the journal: its type, its date (today by default, a future one allowed), the details of its type and a short note. */
export function FollowUpForm({
  action,
  onSubmit,
  onCancel,
}: {
  /** The action being changed; none for a new one. */
  action?: FollowUpAction;
  onSubmit: (body: FollowUpBody) => void;
  onCancel: () => void;
}) {
  const form = useForm<FollowUpFormValues, unknown, FollowUpFormOutput>({
    resolver: zodResolver(followUpFormSchema),
    defaultValues: action
      ? toFormValues(action)
      : { ...emptyFollowUpForm, date: todayIso() },
  });
  const { setFocus } = form;
  useEffect(() => setFocus('type'), [setFocus]);
  const type = useWatch({ control: form.control, name: 'type' });
  const note = useWatch({ control: form.control, name: 'note' });
  const { errors } = form.formState;
  return (
    <form
      noValidate
      className="flex min-h-0 flex-1 flex-col"
      onSubmit={form.handleSubmit((values) => onSubmit(toBody(values)))}
    >
      <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-5 py-4">
        <fieldset
          aria-describedby={errors.type ? 'follow-up-type-error' : undefined}
          className="flex flex-col gap-2"
        >
          <legend className="mb-2 text-sm font-medium">Que as-tu fait ?</legend>
          <div className="grid grid-cols-2 gap-2">
            {actionTypes.map(([code, label]) => {
              const Icon = actionIcons[code];
              return (
                <label
                  key={code}
                  className="relative flex min-h-12 cursor-pointer items-center gap-2 rounded-lg border bg-card px-3 py-2 text-sm has-checked:border-primary has-checked:bg-primary/10 has-checked:font-medium has-focus-visible:ring-[3px] has-focus-visible:ring-ring/50"
                >
                  <input
                    type="radio"
                    value={code}
                    className="peer sr-only"
                    {...form.register('type')}
                  />
                  <Icon aria-hidden className="size-4 shrink-0 text-primary" />
                  {label}
                </label>
              );
            })}
          </div>
          {errors.type && (
            <FieldError id="follow-up-type-error">
              {errors.type.message}
            </FieldError>
          )}
        </fieldset>
        <div className="flex gap-3">
          <DateInput form={form} name="date" label="Date" />
          {type === 'INTERVIEW' && (
            <Field data-invalid={!!errors.time} className="w-32 shrink-0">
              <FieldLabel htmlFor="follow-up-time">Heure</FieldLabel>
              <Input
                id="follow-up-time"
                type="time"
                aria-invalid={!!errors.time}
                aria-describedby={
                  errors.time ? 'follow-up-time-error' : undefined
                }
                {...form.register('time')}
              />
              {errors.time && (
                <FieldError id="follow-up-time-error">
                  {errors.time.message}
                </FieldError>
              )}
            </Field>
          )}
        </div>
        <Details form={form} type={type} />
        <Field data-invalid={!!errors.note}>
          <FieldLabel htmlFor="follow-up-note">Note (facultatif)</FieldLabel>
          <Textarea
            id="follow-up-note"
            rows={3}
            aria-invalid={!!errors.note}
            aria-describedby={
              errors.note
                ? 'follow-up-note-hint follow-up-note-error'
                : 'follow-up-note-hint'
            }
            {...form.register('note')}
          />
          <FieldDescription id="follow-up-note-hint">
            N’écris ni nom ni coordonnées de personnes.{' '}
            <span aria-hidden>
              {note.length}/{noteMaxLength}
            </span>
          </FieldDescription>
          {errors.note && (
            <FieldError id="follow-up-note-error">
              {errors.note.message}
            </FieldError>
          )}
        </Field>
      </div>
      <footer className="flex shrink-0 items-center justify-between gap-3 border-t bg-popover py-3 pr-20 pl-5 sm:pr-5">
        <Button type="button" variant="ghost" onClick={onCancel}>
          Annuler
        </Button>
        <Button type="submit">Enregistrer</Button>
      </footer>
    </form>
  );
}
