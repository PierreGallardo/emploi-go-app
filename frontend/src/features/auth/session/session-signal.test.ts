import { expect, it, vi } from 'vitest';
import { FakeBroadcastChannel, openChannels, otherTab } from '@/test/broadcast';
import { announceSessionChange, onSessionChanged } from './session-signal';
import { testSession } from '@/test/handlers/auth';

it('envoie aux autres onglets un message constant, sans donnée de session', async () => {
  const tab = otherTab();
  announceSessionChange();
  announceSessionChange();

  await vi.waitFor(() => expect(tab.received).toHaveLength(2));
  expect(tab.received).toEqual(['session-changed', 'session-changed']);
});

it('prévient le gestionnaire quand un autre onglet annonce un changement', async () => {
  const handler = vi.fn();
  onSessionChanged(handler);

  otherTab().channel.postMessage('session-changed');

  await vi.waitFor(() => expect(handler).toHaveBeenCalledOnce());
});

it('ne reçoit pas ses propres annonces', async () => {
  const handler = vi.fn();
  onSessionChanged(handler);
  const tab = otherTab();

  announceSessionChange();

  await vi.waitFor(() => expect(tab.received).toHaveLength(1));
  expect(handler).not.toHaveBeenCalled();
});

it.each([
  { cas: 'un texte inconnu', data: 'autre-chose' },
  { cas: 'un objet', data: testSession('jeune_fictif') },
  { cas: 'aucune donnée', data: undefined },
])('ignore un message qui est $cas', async ({ data }) => {
  const handler = vi.fn();
  onSessionChanged(handler);
  const probe = vi.fn();
  onSessionChanged(probe);
  const tab = otherTab().channel;

  tab.postMessage(data);
  tab.postMessage('session-changed');

  await vi.waitFor(() => expect(probe).toHaveBeenCalledOnce());
  expect(handler).toHaveBeenCalledOnce();
});

it('cesse de prévenir un gestionnaire retiré et libère le canal avec le dernier', async () => {
  const kept = vi.fn();
  const removed = vi.fn();
  const stopKept = onSessionChanged(kept);
  onSessionChanged(removed)();
  const tab = otherTab();

  tab.channel.postMessage('session-changed');
  await vi.waitFor(() => expect(kept).toHaveBeenCalledOnce());
  expect(removed).not.toHaveBeenCalled();

  stopKept();
  expect(openChannels()).toBe(1);
  announceSessionChange();
  await vi.waitFor(() => expect(tab.received).toEqual(['session-changed']));
});

it('ne fait rien dans un navigateur sans BroadcastChannel', () => {
  vi.stubGlobal('BroadcastChannel', undefined);

  expect(() => announceSessionChange()).not.toThrow();
  expect(() => onSessionChanged(() => undefined)()).not.toThrow();
});

it.each([
  {
    cas: 'un constructeur qui refuse',
    broken: class {
      constructor() {
        throw new DOMException('Denied', 'SecurityError');
      }
    },
  },
  {
    cas: 'un envoi qui échoue',
    broken: class extends FakeBroadcastChannel {
      override postMessage() {
        throw new DOMException('Closed', 'InvalidStateError');
      }
    },
  },
])('ne fait jamais échouer l’écriture quand le canal a $cas', ({ broken }) => {
  vi.stubGlobal('BroadcastChannel', broken);

  expect(() => announceSessionChange()).not.toThrow();
  expect(() => onSessionChanged(() => undefined)()).not.toThrow();
});
