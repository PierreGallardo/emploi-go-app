import { z } from 'zod';

/** What a young person did with an establishment, in the order of the form. */
export const actionTypes = [
  ['APPLICATION_SENT', 'Candidature envoyée'],
  ['FOLLOW_UP', 'Relance'],
  ['PHONE_CALL', 'Appel'],
  ['ON_SITE_VISIT', 'Passage sur place'],
  ['INTERVIEW', 'Entretien'],
  ['POSITIVE_ANSWER', 'Réponse positive'],
  ['NEGATIVE_ANSWER', 'Réponse négative'],
  ['OTHER', 'Autre'],
] as const;
export type ActionType = (typeof actionTypes)[number][0];
export const actionTypeLabel = (type: ActionType) =>
  actionTypes.find(([code]) => code === type)![1];

// The optional details of each type. Never a name or a contact: they would be personal data about someone else.
export const applicationChannels = [
  ['EMAIL', 'Email'],
  ['WEBSITE', 'Site de l’entreprise'],
  ['MAIL', 'Courrier'],
  ['ON_SITE', 'Sur place'],
] as const;
export const followUpChannels = [
  ['EMAIL', 'Email'],
  ['PHONE', 'Téléphone'],
  ['MAIL', 'Courrier'],
  ['ON_SITE', 'Sur place'],
] as const;
export const callDirections = [
  ['OUTGOING', 'J’ai appelé'],
  ['INCOMING', 'On m’a appelé'],
] as const;
export const visitResults = [
  ['CV_LEFT', 'CV déposé'],
  ['APPOINTMENT_OBTAINED', 'Rendez-vous obtenu'],
  ['TO_COME_BACK', 'À revenir'],
  ['NO_ONE_AVAILABLE', 'Personne de disponible'],
] as const;
export const interviewFormats = [
  ['ON_SITE', 'Sur place'],
  ['VIDEO', 'Visio'],
  ['PHONE', 'Téléphone'],
] as const;
export const contractTypes = [
  ['JOB', 'Emploi'],
  ['WORK_STUDY', 'Alternance'],
  ['INTERNSHIP', 'Stage'],
  ['IMMERSION', 'Immersion'],
  ['SUMMER_JOB', 'Job d’été'],
] as const;
export const refusalReasons = [
  ['NO_OPENING', 'Pas de poste ouvert'],
  ['POSITION_FILLED', 'Poste déjà pourvu'],
  ['PROFILE_MISMATCH', 'Profil non retenu'],
] as const;

export const noteMaxLength = 280;
export const textMaxLength = 80;

const isoDate = /^\d{4}-\d{2}-\d{2}$/;
function isRealDate(value: string) {
  if (!isoDate.test(value)) return false;
  const date = new Date(`${value}T12:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}
const dateSchema = z.string().refine(isRealDate);
const timeSchema = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);

const common = {
  date: dateSchema,
  note: z.string().max(noteMaxLength).nullable(),
};
const text = z.string().max(textMaxLength).nullable();
const code = (options: readonly (readonly [string, string])[]) =>
  z.enum(options.map(([value]) => value)).nullable();

// One shape per type: only the details that belong to it, `null` when left empty.
/** What the journal keeps of one action. */
export const followUpBodySchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('APPLICATION_SENT'),
    ...common,
    channel: code(applicationChannels),
    position: text,
  }),
  z.object({
    type: z.literal('FOLLOW_UP'),
    ...common,
    channel: code(followUpChannels),
  }),
  z.object({
    type: z.literal('PHONE_CALL'),
    ...common,
    direction: code(callDirections),
  }),
  z.object({
    type: z.literal('ON_SITE_VISIT'),
    ...common,
    result: code(visitResults),
  }),
  z.object({
    type: z.literal('INTERVIEW'),
    ...common,
    time: timeSchema.nullable(),
    format: code(interviewFormats),
    position: text,
  }),
  z.object({
    type: z.literal('POSITIVE_ANSWER'),
    ...common,
    contractType: code(contractTypes),
    startDate: dateSchema.nullable(),
  }),
  z.object({
    type: z.literal('NEGATIVE_ANSWER'),
    ...common,
    reason: code(refusalReasons),
  }),
  z.object({ type: z.literal('OTHER'), ...common, label: text }),
]);
export type FollowUpBody = z.infer<typeof followUpBodySchema>;

export const followUpActionSchema = followUpBodySchema.and(
  z.object({ id: z.uuid() }),
);
export type FollowUpAction = z.infer<typeof followUpActionSchema>;

const typeSchema = z.enum(
  actionTypes.map(([value]) => value),
  { error: 'Choisis un type d’action.' },
);
const limited = (max: number) =>
  z.string().trim().max(max, `Utilise au maximum ${max} caractères.`);

export const followUpFormSchema = z
  .object({
    // A group of radios with none checked reads as `null`.
    type: z
      .string()
      .nullish()
      .transform((value) => value ?? '')
      .pipe(typeSchema),
    date: z
      .string()
      .min(1, 'Indique la date.')
      .refine(isRealDate, 'Cette date n’existe pas.'),
    time: z.string(),
    channel: z.string(),
    direction: z.string(),
    result: z.string(),
    format: z.string(),
    contractType: z.string(),
    startDate: z
      .string()
      .refine(
        (value) => !value || isRealDate(value),
        'Cette date n’existe pas.',
      ),
    reason: z.string(),
    position: limited(textMaxLength),
    label: limited(textMaxLength),
    note: limited(noteMaxLength),
  })
  .refine(({ time }) => !time || timeSchema.safeParse(time).success, {
    path: ['time'],
    error: 'Cette heure n’existe pas.',
  });

/** What the form holds: every field as text, whichever type is chosen. */
export type FollowUpFormValues = z.input<typeof followUpFormSchema>;
export type FollowUpFormOutput = z.output<typeof followUpFormSchema>;
type DetailKey = Exclude<keyof FollowUpFormValues, 'type' | 'date' | 'note'>;

export const emptyFollowUpForm: FollowUpFormValues = {
  type: '',
  date: '',
  time: '',
  channel: '',
  direction: '',
  result: '',
  format: '',
  contractType: '',
  startDate: '',
  reason: '',
  position: '',
  label: '',
  note: '',
};

const detailKeys: Record<ActionType, DetailKey[]> = {
  APPLICATION_SENT: ['channel', 'position'],
  FOLLOW_UP: ['channel'],
  PHONE_CALL: ['direction'],
  ON_SITE_VISIT: ['result'],
  INTERVIEW: ['time', 'format', 'position'],
  POSITIVE_ANSWER: ['contractType', 'startDate'],
  NEGATIVE_ANSWER: ['reason'],
  OTHER: ['label'],
};

/** The body of the type chosen: the details of the other types, still in the form, are left out. */
export function toBody(values: FollowUpFormOutput): FollowUpBody {
  return followUpBodySchema.parse({
    type: values.type,
    date: values.date,
    note: values.note || null,
    ...Object.fromEntries(
      detailKeys[values.type as ActionType].map((key) => [
        key,
        values[key] || null,
      ]),
    ),
  });
}

/** An action as the form shows it, to change it. */
export function toFormValues(action: FollowUpAction): FollowUpFormValues {
  const details = Object.entries(action).filter(
    ([key]) => key !== 'id' && key !== 'note',
  );
  return {
    ...emptyFollowUpForm,
    ...Object.fromEntries(details.map(([key, value]) => [key, value ?? ''])),
    note: action.note ?? '',
  };
}
