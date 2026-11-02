import { describe, expect, it } from 'vitest';
import {
  emptyFollowUpForm,
  followUpFormSchema,
  noteMaxLength,
  textMaxLength,
  type FollowUpFormValues,
} from './follow-up-schemas';

const otherAction: FollowUpFormValues = {
  ...emptyFollowUpForm,
  type: 'OTHER',
  date: '2020-03-12',
  label: 'Visite de l’atelier',
};
const interview: FollowUpFormValues = {
  ...emptyFollowUpForm,
  type: 'INTERVIEW',
  date: '2020-03-12',
};
const positiveAnswer: FollowUpFormValues = {
  ...emptyFollowUpForm,
  type: 'POSITIVE_ANSWER',
  date: '2020-03-12',
};

const issuesOf = (values: FollowUpFormValues) => {
  const result = followUpFormSchema.safeParse(values);
  return result.success
    ? []
    : result.error.issues.map(({ path, message }) => ({
        path: path.join('.'),
        message,
      }));
};

describe('followUpFormSchema accepte', () => {
  it.each([
    { case: 'une action du jour sans détail', values: otherAction },
    {
      case: 'un libellé à exactement la limite',
      values: { ...otherAction, label: 'x'.repeat(textMaxLength) },
    },
    {
      case: 'une note à exactement la limite',
      values: { ...otherAction, note: 'x'.repeat(noteMaxLength) },
    },
    {
      case: 'une heure de fin de journée',
      values: { ...interview, time: '23:59' },
    },
    {
      case: 'une date de début vide',
      values: { ...positiveAnswer, startDate: '' },
    },
    {
      case: 'une date de début valide',
      values: { ...positiveAnswer, startDate: '2020-04-01' },
    },
  ])('$case', ({ values }) => {
    expect(issuesOf(values)).toEqual([]);
  });
});

describe('followUpFormSchema refuse', () => {
  it.each([
    {
      // Zod also reports the empty date as not existing: the form shows the first message, « Indique la date. ».
      case: 'une date vide',
      values: { ...otherAction, date: '' },
      issues: [
        { path: 'date', message: 'Indique la date.' },
        { path: 'date', message: 'Cette date n’existe pas.' },
      ],
    },
    {
      case: 'un jour inexistant (30 février)',
      values: { ...otherAction, date: '2020-02-30' },
      issues: [{ path: 'date', message: 'Cette date n’existe pas.' }],
    },
    {
      case: 'un mois inexistant',
      values: { ...otherAction, date: '2020-13-01' },
      issues: [{ path: 'date', message: 'Cette date n’existe pas.' }],
    },
    {
      case: 'une heure inexistante (25:00)',
      values: { ...interview, time: '25:00' },
      issues: [{ path: 'time', message: 'Cette heure n’existe pas.' }],
    },
    {
      case: 'une heure inexistante (12:60)',
      values: { ...interview, time: '12:60' },
      issues: [{ path: 'time', message: 'Cette heure n’existe pas.' }],
    },
    {
      case: 'une date de début inexistante',
      values: { ...positiveAnswer, startDate: '2020-02-30' },
      issues: [{ path: 'startDate', message: 'Cette date n’existe pas.' }],
    },
    {
      case: 'un type absent',
      values: { ...otherAction, type: '' },
      issues: [{ path: 'type', message: 'Choisis un type d’action.' }],
    },
    {
      case: 'un libellé au-delà de la limite',
      values: { ...otherAction, label: 'x'.repeat(textMaxLength + 1) },
      issues: [
        {
          path: 'label',
          message: `Utilise au maximum ${textMaxLength} caractères.`,
        },
      ],
    },
    {
      case: 'un poste au-delà de la limite',
      values: { ...interview, position: 'x'.repeat(textMaxLength + 1) },
      issues: [
        {
          path: 'position',
          message: `Utilise au maximum ${textMaxLength} caractères.`,
        },
      ],
    },
    {
      case: 'une note au-delà de la limite',
      values: { ...otherAction, note: 'x'.repeat(noteMaxLength + 1) },
      issues: [
        {
          path: 'note',
          message: `Utilise au maximum ${noteMaxLength} caractères.`,
        },
      ],
    },
  ])('$case', ({ values, issues }) => {
    expect(issuesOf(values)).toEqual(issues);
  });
});
