import { describe, expect, it, vi } from 'vitest';
import { readActions, sortActions, writeActions } from './follow-up-store';
import type { FollowUpAction } from './follow-up-schemas';

const siret = '00000000000000';
const phoneCall = {
  id: '6f1c2a4e-8d3b-4f5a-9c7e-1a2b3c4d5e6f',
  type: 'PHONE_CALL',
  date: '2020-03-02',
  note: null,
  direction: null,
} satisfies FollowUpAction;
const otherAction = {
  id: '0b6f6f7e-1c2d-4e3f-8a9b-7c6d5e4f3a2b',
  type: 'OTHER',
  date: '2020-03-02',
  note: null,
  label: 'Visite',
} satisfies FollowUpAction;
const invalidEntry = { type: 'AUTRE', date: '2020-03-02' };

// The journal is stored under the establishment's key: read it back rather than copying the key.
const storedKey = () => {
  const [key] = Object.keys(localStorage);
  return key!;
};

describe('sortActions', () => {
  it('trie les actions de la plus récente à la plus ancienne', () => {
    const older = { ...phoneCall, id: '1a1a1a1a-0000-4000-8000-000000000001' };
    const newer = {
      ...phoneCall,
      id: '1a1a1a1a-0000-4000-8000-000000000002',
      date: '2020-03-20',
    } satisfies FollowUpAction;

    expect(sortActions([older, newer]).map(({ id }) => id)).toEqual([
      newer.id,
      older.id,
    ]);
  });

  it('trie deux entretiens du même jour de l’heure la plus tardive à la plus tôt', () => {
    const morning = {
      id: '1a1a1a1a-0000-4000-8000-000000000003',
      type: 'INTERVIEW',
      date: '2020-03-12',
      note: null,
      time: '09:00',
      format: null,
      position: null,
    } satisfies FollowUpAction;
    const afternoon = {
      ...morning,
      id: '1a1a1a1a-0000-4000-8000-000000000004',
      time: '14:30',
    } satisfies FollowUpAction;

    expect(sortActions([morning, afternoon]).map(({ id }) => id)).toEqual([
      afternoon.id,
      morning.id,
    ]);
  });

  it('garde l’ordre d’origine pour deux actions au même moment', () => {
    expect(sortActions([phoneCall, otherAction]).map(({ id }) => id)).toEqual([
      phoneCall.id,
      otherAction.id,
    ]);
  });
});

describe('readActions', () => {
  it('rend un journal vide quand rien n’est gardé pour l’établissement', () => {
    expect(readActions(siret)).toEqual([]);
  });

  it('rend un journal vide quand ce qui est gardé n’est pas du JSON', () => {
    writeActions(siret, [phoneCall]);
    localStorage.setItem(storedKey(), '[{"type":"AUTRE"}');

    expect(readActions(siret)).toEqual([]);
  });

  it.each([
    { case: 'un objet', stored: '{}' },
    { case: 'la valeur nulle', stored: 'null' },
    { case: 'un texte', stored: '"journal"' },
  ])('rend un journal vide quand ce qui est gardé est $case', ({ stored }) => {
    writeActions(siret, [phoneCall]);
    localStorage.setItem(storedKey(), stored);

    expect(readActions(siret)).toEqual([]);
  });

  it('garde les entrées valides quand une autre entrée n’a pas le bon format', () => {
    writeActions(siret, [phoneCall]);
    localStorage.setItem(
      storedKey(),
      JSON.stringify([phoneCall, invalidEntry]),
    );

    expect(readActions(siret)).toEqual([phoneCall]);
  });

  it('ne perd pas les entrées valides à l’écriture d’une nouvelle action', () => {
    writeActions(siret, [phoneCall]);
    localStorage.setItem(
      storedKey(),
      JSON.stringify([phoneCall, invalidEntry]),
    );
    const added = {
      id: '2b2b2b2b-0000-4000-8000-000000000005',
      type: 'OTHER',
      date: '2020-03-20',
      note: null,
      label: 'Visite',
    } satisfies FollowUpAction;

    writeActions(siret, [added, ...readActions(siret)]);

    expect(readActions(siret).map(({ id }) => id)).toEqual([
      added.id,
      phoneCall.id,
    ]);
  });
});

describe('writeActions', () => {
  it('relance l’erreur quand le navigateur refuse l’écriture', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Quota dépassé', 'QuotaExceededError');
    });

    expect(() => writeActions(siret, [phoneCall])).toThrow('Quota dépassé');
  });
});
