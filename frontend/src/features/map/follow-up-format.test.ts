import { describe, expect, it } from 'vitest';
import { actionDetails, formatTime, isUpcoming } from './follow-up-format';
import type { FollowUpAction } from './follow-up-schemas';

const id = '6f1c2a4e-8d3b-4f5a-9c7e-1a2b3c4d5e6f';
const now = new Date(2026, 9, 27, 10, 0);

describe('formatTime', () => {
  it.each([
    { time: '14:30', text: '14 h 30' },
    { time: '09:00', text: '9 h' },
  ])('écrit $time en « $text »', ({ time, text }) => {
    expect(formatTime(time)).toBe(text);
  });
});

describe('isUpcoming', () => {
  it.each([
    {
      case: 'un jour suivant sans heure',
      action: { date: '2026-10-28', time: null },
      upcoming: true,
    },
    {
      case: 'un jour suivant avec une heure plus tôt',
      action: { date: '2026-10-28', time: '09:00' },
      upcoming: true,
    },
    {
      case: 'un jour passé sans heure',
      action: { date: '2026-10-26', time: null },
      upcoming: false,
    },
    {
      case: 'un jour passé avec une heure plus tard',
      action: { date: '2026-10-26', time: '23:00' },
      upcoming: false,
    },
    {
      case: 'aujourd’hui sans heure',
      action: { date: '2026-10-27', time: null },
      upcoming: false,
    },
    {
      case: 'aujourd’hui à une heure plus tard',
      action: { date: '2026-10-27', time: '14:30' },
      upcoming: true,
    },
    {
      case: 'aujourd’hui à l’heure actuelle',
      action: { date: '2026-10-27', time: '10:00' },
      upcoming: false,
    },
    {
      case: 'aujourd’hui à une heure passée',
      action: { date: '2026-10-27', time: '09:00' },
      upcoming: false,
    },
  ])('$case est à venir : $upcoming', ({ action, upcoming }) => {
    expect(isUpcoming(action, now)).toBe(upcoming);
  });
});

describe('actionDetails', () => {
  it.each([
    {
      case: 'une candidature avec son canal et son poste',
      action: {
        id,
        type: 'APPLICATION_SENT',
        date: '2020-03-02',
        note: null,
        channel: 'EMAIL',
        position: 'Vendeur',
      },
      details: ['Par email', 'Poste : Vendeur'],
    },
    {
      case: 'une candidature sans détail',
      action: {
        id,
        type: 'APPLICATION_SENT',
        date: '2020-03-02',
        note: null,
        channel: null,
        position: null,
      },
      details: [],
    },
    {
      case: 'une relance par téléphone',
      action: {
        id,
        type: 'FOLLOW_UP',
        date: '2020-03-02',
        note: null,
        channel: 'PHONE',
      },
      details: ['Par téléphone'],
    },
    {
      case: 'un appel passé par la personne',
      action: {
        id,
        type: 'PHONE_CALL',
        date: '2020-03-02',
        note: null,
        direction: 'OUTGOING',
      },
      details: ['J’ai appelé'],
    },
    {
      case: 'un passage sur place avec un CV déposé',
      action: {
        id,
        type: 'ON_SITE_VISIT',
        date: '2020-03-02',
        note: null,
        result: 'CV_LEFT',
      },
      details: ['CV déposé'],
    },
    {
      case: 'un entretien en visio sans poste',
      action: {
        id,
        type: 'INTERVIEW',
        date: '2020-03-02',
        note: null,
        time: '14:30',
        format: 'VIDEO',
        position: null,
      },
      details: ['Visio'],
    },
    {
      case: 'un entretien sans format avec un poste',
      action: {
        id,
        type: 'INTERVIEW',
        date: '2020-03-02',
        note: null,
        time: null,
        format: null,
        position: 'Caissier',
      },
      details: ['Poste : Caissier'],
    },
    {
      case: 'une réponse positive avec son contrat et sa date de début',
      action: {
        id,
        type: 'POSITIVE_ANSWER',
        date: '2020-03-02',
        note: null,
        contractType: 'JOB',
        startDate: '2020-04-01',
      },
      details: ['Emploi', 'Début le 1 avril 2020'],
    },
    {
      case: 'une réponse négative avec sa raison',
      action: {
        id,
        type: 'NEGATIVE_ANSWER',
        date: '2020-03-02',
        note: null,
        reason: 'POSITION_FILLED',
      },
      details: ['Poste déjà pourvu'],
    },
    {
      case: 'une autre action avec son libellé',
      action: {
        id,
        type: 'OTHER',
        date: '2020-03-02',
        note: null,
        label: 'Visite de l’atelier',
      },
      details: ['Visite de l’atelier'],
    },
  ] satisfies { case: string; action: FollowUpAction; details: string[] }[])(
    'détaille $case',
    ({ action, details }) => {
      expect(actionDetails(action)).toEqual(details);
    },
  );
});
