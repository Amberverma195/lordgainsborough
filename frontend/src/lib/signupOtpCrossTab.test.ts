import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyCrossTabPayload,
  parseCrossTabPayload,
  SignupOtpCrossTabPayload,
  subscribeSignupOtpCrossTab,
  toCrossTabPayload,
} from './signupOtpCrossTab';
import { SignupOtpChallengeState } from './signupOtp';

type Listener = (event: { data: SignupOtpCrossTabPayload }) => void;

function createMockBroadcastHub() {
  const listeners = new Map<string, Set<Listener>>();

  return {
    createChannel(name: string) {
      if (!listeners.has(name)) {
        listeners.set(name, new Set());
      }
      const set = listeners.get(name)!;
      let onmessage: Listener | null = null;
      const channel = {
        set onmessage(handler: Listener | null) {
          if (onmessage) set.delete(onmessage);
          onmessage = handler;
          if (handler) set.add(handler);
        },
        get onmessage() {
          return onmessage;
        },
        postMessage(data: SignupOtpCrossTabPayload) {
          for (const listener of [...set]) {
            if (listener !== onmessage) {
              listener({ data });
            }
          }
        },
        close() {
          if (onmessage) set.delete(onmessage);
          onmessage = null;
        },
      };
      return channel;
    },
  };
}

function createMemoryStorage() {
  const store = new Map<string, string>();
  const storageListeners = new Set<(event: StorageEvent) => void>();

  const storage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      const oldValue = store.get(key) ?? null;
      store.set(key, value);
      // Simulate browser: storage event only for other documents.
      // Tests invoke notifyOtherTabs explicitly.
      void oldValue;
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => store.clear(),
    notifyOtherTabs(key: string, newValue: string, skipOrigin?: string) {
      const event = {
        key,
        newValue,
        oldValue: null,
        storageArea: storage,
      } as unknown as StorageEvent;
      for (const listener of storageListeners) {
        listener(event);
      }
      void skipOrigin;
    },
    addListener(listener: (event: StorageEvent) => void) {
      storageListeners.add(listener);
      return () => storageListeners.delete(listener);
    },
  };

  return storage;
}

describe('signupOtpCrossTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('toCrossTabPayload never includes otp, digests, or tokens', () => {
    const state: SignupOtpChallengeState = {
      email: 'a@b.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      challengeExpiresAt: '2026-07-13T12:30:00.000Z',
      resendsRemaining: 4,
      code: 'OTP_SENT',
    };
    const payload = toCrossTabPayload(state, 'challenge-updated', 'tab-a');
    expect(payload).not.toHaveProperty('otp');
    expect(payload).not.toHaveProperty('digest');
    expect(payload).not.toHaveProperty('token');
    expect(JSON.stringify(payload)).not.toMatch(/"otp"\s*:/);
    expect(JSON.stringify(payload)).not.toMatch(/"digest"\s*:/);
    expect(JSON.stringify(payload)).not.toMatch(/"token"\s*:/);
    expect(payload.challengeId).toBe('c1');
    expect(payload.resendsRemaining).toBe(4);
    expect(payload.code).toBe('OTP_SENT');
  });

  it('parseCrossTabPayload strips accidental sensitive fields', () => {
    const payload = parseCrossTabPayload(JSON.stringify({
      type: 'challenge-updated',
      challengeId: 'c1',
      code: 'OTP_SENT',
      otp: '123456',
      token: 'jwt-secret',
      digest: 'abc',
      originTabId: 'tab-a',
      updatedAt: 1,
    }));
    expect(payload).not.toBeNull();
    expect(payload).not.toHaveProperty('otp');
    expect(payload).not.toHaveProperty('token');
    expect(payload).not.toHaveProperty('digest');
  });

  it('two tabs opened before initial send: first send updates the second tab', () => {
    const hub = createMockBroadcastHub();
    const tabBUpdates: SignupOtpCrossTabPayload[] = [];

    const tabA = subscribeSignupOtpCrossTab(() => {}, {
      tabId: 'tab-a',
      createBroadcastChannel: (name) => hub.createChannel(name),
      storage: null,
    });
    const tabB = subscribeSignupOtpCrossTab((payload) => {
      tabBUpdates.push(payload);
    }, {
      tabId: 'tab-b',
      createBroadcastChannel: (name) => hub.createChannel(name),
      storage: null,
    });

    tabA.publish(toCrossTabPayload({
      email: 'a@b.com',
      challengeId: 'fresh-1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      challengeExpiresAt: '2026-07-13T12:30:00.000Z',
      resendsRemaining: 5,
      code: 'OTP_SENT',
    }, 'challenge-updated', tabA.tabId));

    expect(tabBUpdates).toHaveLength(1);
    expect(tabBUpdates[0].challengeId).toBe('fresh-1');
    expect(tabBUpdates[0].code).toBe('OTP_SENT');
    expect(tabBUpdates[0].resendsRemaining).toBe(5);

    tabA.close();
    tabB.close();
  });

  it('two tabs resending simultaneously: both see the winning backend state', () => {
    const hub = createMockBroadcastHub();
    const tabAState: SignupOtpChallengeState[] = [];
    const tabBState: SignupOtpChallengeState[] = [];

    const tabA = subscribeSignupOtpCrossTab((payload) => {
      tabAState.push(applyCrossTabPayload(null, payload)!);
    }, {
      tabId: 'tab-a',
      createBroadcastChannel: (name) => hub.createChannel(name),
      storage: null,
    });
    const tabB = subscribeSignupOtpCrossTab((payload) => {
      tabBState.push(applyCrossTabPayload(null, payload)!);
    }, {
      tabId: 'tab-b',
      createBroadcastChannel: (name) => hub.createChannel(name),
      storage: null,
    });

    // Tab A wins the backend race and broadcasts.
    tabA.publish(toCrossTabPayload({
      email: 'a@b.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:01:00.000Z',
      nextResendAt: '2026-07-13T12:01:30.000Z',
      resendsRemaining: 4,
      code: 'OTP_SENT',
    }, 'challenge-updated', tabA.tabId));

    // Tab B receives cooldown from its own 429, then receives A's broadcast (latest wins in UI).
    tabB.publish(toCrossTabPayload({
      email: 'a@b.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:01:00.000Z',
      nextResendAt: '2026-07-13T12:01:30.000Z',
      resendsRemaining: 4,
      code: 'OTP_RESEND_COOLDOWN',
    }, 'challenge-updated', tabB.tabId));

    expect(tabBState[0].resendsRemaining).toBe(4);
    expect(tabBState[0].nextResendAt).toBe('2026-07-13T12:01:30.000Z');
    expect(tabAState[0].code).toBe('OTP_RESEND_COOLDOWN');

    tabA.close();
    tabB.close();
  });

  it('one tab successfully verifying notifies the other tab', () => {
    const hub = createMockBroadcastHub();
    let verifiedOnB = false;

    const tabA = subscribeSignupOtpCrossTab(() => {}, {
      tabId: 'tab-a',
      createBroadcastChannel: (name) => hub.createChannel(name),
      storage: null,
    });
    const tabB = subscribeSignupOtpCrossTab((payload) => {
      if (payload.type === 'verified' || payload.code === 'OTP_VERIFIED') {
        verifiedOnB = true;
      }
    }, {
      tabId: 'tab-b',
      createBroadcastChannel: (name) => hub.createChannel(name),
      storage: null,
    });

    tabA.publish({
      type: 'verified',
      code: 'OTP_VERIFIED',
      challengeId: 'c1',
      originTabId: tabA.tabId,
      updatedAt: Date.now(),
    });

    expect(verifiedOnB).toBe(true);
    tabA.close();
    tabB.close();
  });

  it('one tab holding stale state is overwritten by a fresher broadcast', () => {
    const stale: SignupOtpChallengeState = {
      email: 'a@b.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:05.000Z',
      resendsRemaining: 5,
      code: 'OTP_SENT',
    };

    const freshPayload: SignupOtpCrossTabPayload = {
      type: 'challenge-updated',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:20.000Z',
      nextResendAt: '2026-07-13T12:00:50.000Z',
      resendsRemaining: 3,
      challengeExpiresAt: '2026-07-13T12:30:00.000Z',
      code: 'OTP_SENT',
      email: 'a@b.com',
      originTabId: 'tab-fresh',
      updatedAt: Date.now(),
    };

    const updated = applyCrossTabPayload(stale, freshPayload)!;
    expect(updated.nextResendAt).toBe('2026-07-13T12:00:50.000Z');
    expect(updated.resendsRemaining).toBe(3);
    expect(updated.serverTime).toBe('2026-07-13T12:00:20.000Z');
  });

  it('localStorage storage-event fallback syncs when BroadcastChannel is unavailable', () => {
    const memory = createMemoryStorage();
    const remoteUpdates: SignupOtpCrossTabPayload[] = [];

    const tabA = subscribeSignupOtpCrossTab(() => {}, {
      tabId: 'tab-a',
      createBroadcastChannel: () => null,
      storage: memory as unknown as Storage,
      addStorageListener: (listener) => memory.addListener(listener),
    });
    const tabB = subscribeSignupOtpCrossTab((payload) => {
      remoteUpdates.push(payload);
    }, {
      tabId: 'tab-b',
      createBroadcastChannel: () => null,
      storage: memory as unknown as Storage,
      addStorageListener: (listener) => memory.addListener(listener),
    });

    const payload = toCrossTabPayload({
      email: 'a@b.com',
      challengeId: 'c-storage',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      resendsRemaining: 5,
      code: 'OTP_SENT',
    }, 'challenge-updated', tabA.tabId);

    tabA.publish(payload);
    // Simulate the browser delivering a storage event to the other tab.
    const written = memory.getItem('signupOtpChallengeSync');
    expect(written).toBeTruthy();
    memory.notifyOtherTabs('signupOtpChallengeSync', written!);

    expect(remoteUpdates).toHaveLength(1);
    expect(remoteUpdates[0].challengeId).toBe('c-storage');

    tabA.close();
    tabB.close();
  });

  it('verification success persists authentication before notifying other tabs', () => {
    const memory = createMemoryStorage();
    const sequence: string[] = [];

    const tabA = subscribeSignupOtpCrossTab(() => {}, {
      tabId: 'tab-a',
      createBroadcastChannel: () => null,
      storage: memory as unknown as Storage,
      addStorageListener: (listener) => memory.addListener(listener),
    });

    const tabB = subscribeSignupOtpCrossTab((payload) => {
      if (payload.type === 'verified') {
        // Tab B reacts to verified: checks if token and user exist in storage
        const token = memory.getItem('token');
        const user = memory.getItem('user');
        sequence.push(`tabB_received_verified:hasToken=${!!token}:hasUser=${!!user}`);
      }
    }, {
      tabId: 'tab-b',
      createBroadcastChannel: () => null,
      storage: memory as unknown as Storage,
      addStorageListener: (listener) => memory.addListener(listener),
    });

    // In Tab A: Persist auth FIRST
    memory.setItem('token', 'valid-jwt-token');
    memory.setItem('user', JSON.stringify({ id: 'u1', email: 'test@example.com' }));
    sequence.push('tabA_persisted_auth');

    // In Tab A: Broadcast verified SECOND
    const payload = toCrossTabPayload({
      email: 'test@example.com',
      challengeId: 'c-verify-seq',
      code: 'OTP_VERIFIED',
    }, 'verified', tabA.tabId);
    tabA.publish(payload);

    const syncData = memory.getItem('signupOtpChallengeSync');
    memory.notifyOtherTabs('signupOtpChallengeSync', syncData!);

    expect(sequence).toEqual([
      'tabA_persisted_auth',
      'tabB_received_verified:hasToken=true:hasUser=true',
    ]);

    tabA.close();
    tabB.close();
  });
});
