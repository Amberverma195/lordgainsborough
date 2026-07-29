import { describe, expect, it } from 'vitest';
import {
  applyPasswordResetCrossTabPayload,
  parsePasswordResetCrossTabPayload,
  PASSWORD_RESET_SYNC_STORAGE_KEY,
  PasswordResetCrossTabPayload,
  subscribePasswordResetCrossTab,
  toPasswordResetCrossTabPayload,
} from './passwordResetCrossTab';
import type { PasswordResetChallengeState } from './passwordReset';

type Listener = (event: { data: PasswordResetCrossTabPayload }) => void;

function createBroadcastHub() {
  const listeners = new Set<Listener>();
  return {
    createChannel() {
      let onmessage: Listener | null = null;
      return {
        get onmessage() {
          return onmessage;
        },
        set onmessage(listener: Listener | null) {
          if (onmessage) listeners.delete(onmessage);
          onmessage = listener;
          if (listener) listeners.add(listener);
        },
        postMessage(data: PasswordResetCrossTabPayload) {
          for (const listener of [...listeners]) {
            if (listener !== onmessage) listener({ data });
          }
        },
        close() {
          if (onmessage) listeners.delete(onmessage);
        },
      };
    },
  };
}

function challengeState(): PasswordResetChallengeState {
  return {
    email: 'member@example.com',
    challengeId: 'challenge-1',
    serverTime: '2026-07-13T12:00:00.000Z',
    nextResendAt: '2026-07-13T12:00:30.000Z',
    resendsRemaining: 4,
    observedAt: Date.parse('2026-07-13T12:00:00.000Z'),
  };
}

describe('password reset cross-tab sync', () => {
  it('synchronizes safe challenge state through BroadcastChannel', () => {
    const hub = createBroadcastHub();
    const received: PasswordResetCrossTabPayload[] = [];
    const tabA = subscribePasswordResetCrossTab(() => {}, {
      tabId: 'tab-a',
      createBroadcastChannel: () => hub.createChannel(),
      storage: null,
    });
    const tabB = subscribePasswordResetCrossTab(payload => received.push(payload), {
      tabId: 'tab-b',
      createBroadcastChannel: () => hub.createChannel(),
      storage: null,
    });

    tabA.publish(toPasswordResetCrossTabPayload(
      challengeState(),
      'challenge-updated',
      tabA.tabId
    ));

    expect(received).toHaveLength(1);
    expect(applyPasswordResetCrossTabPayload(received[0])).toEqual(challengeState());
    tabA.close();
    tabB.close();
  });

  it('strips secrets from malformed cross-tab input', () => {
    const payload = parsePasswordResetCrossTabPayload(JSON.stringify({
      ...toPasswordResetCrossTabPayload(challengeState(), 'challenge-updated', 'tab-a'),
      otp: '123456',
      password: 'Plaintext1!',
      token: 'secret',
    }));
    expect(payload).not.toBeNull();
    expect(payload).not.toHaveProperty('otp');
    expect(payload).not.toHaveProperty('password');
    expect(payload).not.toHaveProperty('token');
  });

  it('uses the localStorage event fallback when BroadcastChannel is unavailable', () => {
    const values = new Map<string, string>();
    const listeners = new Set<(event: StorageEvent) => void>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    } as unknown as Storage;
    const received: PasswordResetCrossTabPayload[] = [];
    const tab = subscribePasswordResetCrossTab(payload => received.push(payload), {
      tabId: 'tab-b',
      createBroadcastChannel: () => null,
      storage,
      addStorageListener: listener => {
        listeners.add(listener);
        return () => listeners.delete(listener);
      },
    });

    const raw = JSON.stringify(toPasswordResetCrossTabPayload(
      challengeState(),
      'challenge-updated',
      'tab-a'
    ));
    for (const listener of listeners) {
      listener({ key: PASSWORD_RESET_SYNC_STORAGE_KEY, newValue: raw } as StorageEvent);
    }

    expect(received).toHaveLength(1);
    expect(received[0].challengeId).toBe('challenge-1');
    tab.close();
  });
});
