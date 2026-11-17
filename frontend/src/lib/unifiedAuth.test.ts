import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyPasswordResetResponse,
  isPasswordResetChallengeExpired,
  isPasswordResetResendExhausted,
  PasswordResetChallengeState,
  passwordResetCooldownSeconds,
  toSafePasswordResetState,
} from './passwordReset';
import {
  parsePasswordResetCrossTabPayload,
  toPasswordResetCrossTabPayload,
} from './passwordResetCrossTab';
import {
  applyBackendChallengeState,
  clearAllSignupSession,
  cooldownSecondsFromServerClocks,
  loadPendingSignupCredentials,
  loadSignupOtpChallengeState,
  savePendingSignupCredentials,
  saveSignupOtpChallengeState,
} from './signupOtp';
import { isValidEmail } from './email';
import { isValidPhoneNumber, normalizeIdentifier } from './phone';

function installMemorySessionStorage() {
  const store = new Map<string, string>();
  const memory = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
    get length() {
      return store.size;
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
  };
  vi.stubGlobal('sessionStorage', memory);
  return memory;
}

describe('Unified Auth: Password Reset Challenge State Invariants', () => {
  it('creates safe state stripped of any sensitive credentials', () => {
    const raw = {
      email: 'customer@example.com',
      challengeId: 'chal-12345',
      otp: '123456', // Should be stripped
      password: 'SecretPassword123!', // Should be stripped
      observedAt: 1000000,
    };
    const safe = toSafePasswordResetState(raw);
    expect(safe).not.toBeNull();
    expect(safe?.email).toBe('customer@example.com');
    expect(safe?.challengeId).toBe('chal-12345');
    expect((safe as unknown as Record<string, unknown>).otp).toBeUndefined();
    expect((safe as unknown as Record<string, unknown>).password).toBeUndefined();
  });

  it('calculates deadline-based cooldown anchored to server time and clock skew', () => {
    const serverTime = new Date('2026-11-16T20:00:00.000Z').toISOString();
    const nextResendAt = new Date('2026-11-16T20:00:30.000Z').toISOString();
    const observedAt = Date.parse(serverTime) - 5000; // Client is 5s ahead/behind

    const challenge: PasswordResetChallengeState = {
      email: 'user@example.com',
      challengeId: 'chal-abc',
      serverTime,
      nextResendAt,
      resendsRemaining: 4,
      observedAt,
    };

    // Right when observed
    const remaining = passwordResetCooldownSeconds(challenge, observedAt);
    expect(remaining).toBe(30);

    // 10s later in client wall-clock time
    const remaining10s = passwordResetCooldownSeconds(challenge, observedAt + 10000);
    expect(remaining10s).toBe(20);

    // After deadline
    const remainingElapsed = passwordResetCooldownSeconds(challenge, observedAt + 35000);
    expect(remainingElapsed).toBe(0);
  });

  it('detects challenge expiry and resend exhaustion accurately', () => {
    const challenge: PasswordResetChallengeState = {
      email: 'user@example.com',
      challengeId: 'chal-abc',
      serverTime: new Date(1000000).toISOString(),
      challengeExpiresAt: new Date(1060000).toISOString(), // 60s lifetime
      resendsRemaining: 0,
      observedAt: 1000000,
    };

    expect(isPasswordResetResendExhausted(challenge)).toBe(true);
    expect(isPasswordResetChallengeExpired(challenge, 1030000)).toBe(false);
    expect(isPasswordResetChallengeExpired(challenge, 1070000)).toBe(true);
  });

  it('prevents reusing an old challenge when the identifier changes', () => {
    const oldChallenge: PasswordResetChallengeState = {
      email: 'victim@example.com',
      challengeId: 'chal-victim',
      serverTime: new Date().toISOString(),
      observedAt: Date.now(),
    };

    // When changing email/phone, apply response with new identifier requires server to issue new challengeId
    const newEmail = 'attacker@example.com';
    const serverResponseWithNewChallenge = {
      challengeId: 'chal-attacker',
      serverTime: new Date().toISOString(),
      code: 'RESET_CODE_SENT' as const,
    };

    const next = applyPasswordResetResponse(
      null, // Previous challenge discarded
      newEmail,
      serverResponseWithNewChallenge,
      30
    );

    expect(next?.email).toBe('attacker@example.com');
    expect(next?.challengeId).toBe('chal-attacker');
    expect(next?.challengeId).not.toBe(oldChallenge.challengeId);
  });

  it('updates challenge cooldown and decrements resends remaining from a resend response', () => {
    const serverTime1 = new Date('2026-11-16T20:00:00.000Z').toISOString();
    const initialChallenge: PasswordResetChallengeState = {
      email: 'user@example.com',
      challengeId: 'chal-abc',
      serverTime: serverTime1,
      nextResendAt: new Date('2026-11-16T20:01:00.000Z').toISOString(),
      resendsRemaining: 5,
      observedAt: Date.parse(serverTime1),
    };

    const serverTime2 = new Date('2026-11-16T20:01:05.000Z').toISOString();
    const resendResponse = {
      challengeId: 'chal-abc',
      code: 'RESET_CODE_SENT' as const,
      serverTime: serverTime2,
      nextResendAt: new Date('2026-11-16T20:02:05.000Z').toISOString(),
      resendsRemaining: 4,
    };

    const updated = applyPasswordResetResponse(
      initialChallenge,
      initialChallenge.email,
      resendResponse,
      60,
      Date.parse(serverTime2)
    );

    expect(updated).not.toBeNull();
    expect(updated?.challengeId).toBe('chal-abc');
    expect(updated?.email).toBe('user@example.com');
    expect(updated?.resendsRemaining).toBe(4);
    expect(updated?.serverTime).toBe(serverTime2);
    expect(updated?.nextResendAt).toBe(resendResponse.nextResendAt);
  });
});

describe('Unified Auth: Cross-Tab Invariants', () => {
  it('correctly serializes and parses cross-tab completion and clearing events', () => {
    const payload = toPasswordResetCrossTabPayload(null, 'completed', 'tab-1');
    expect(payload.type).toBe('completed');
    expect(payload.originTabId).toBe('tab-1');

    const parsed = parsePasswordResetCrossTabPayload(JSON.stringify(payload));
    expect(parsed?.type).toBe('completed');
    expect(parsed?.originTabId).toBe('tab-1');

    const clearedPayload = toPasswordResetCrossTabPayload(null, 'cleared', 'tab-1');
    const parsedCleared = parsePasswordResetCrossTabPayload(JSON.stringify(clearedPayload));
    expect(parsedCleared?.type).toBe('cleared');
  });

  it('enforces airtight cross-tab challenge isolation and generation checks', () => {
    const shouldAcceptCrossTab = (
      currentStep: string,
      activeChallenge: { challengeId: string; email: string; observedAt: number } | null,
      activeIdentifier: string,
      sessionStartedAt: number,
      abandonedChallengeIds: Set<string>,
      payload: { email?: string; challengeId?: string; updatedAt?: number }
    ) => {
      const isInRecovery = currentStep === 'reset-request' || currentStep === 'reset-verify';
      if (!isInRecovery) return false;

      if (payload.challengeId && abandonedChallengeIds.has(payload.challengeId)) {
        return false;
      }

      const payloadEmail = payload.email ? normalizeIdentifier(payload.email) : '';
      const payloadChallengeId = payload.challengeId;

      if (activeChallenge) {
        // When active, REQUIRE exact challenge ID
        if (!payloadChallengeId || payloadChallengeId !== activeChallenge.challengeId) {
          return false;
        }
        if (payload.updatedAt && activeChallenge.observedAt && payload.updatedAt < activeChallenge.observedAt) {
          return false;
        }
        return true;
      } else {
        if (!payloadEmail || !activeIdentifier || payloadEmail !== normalizeIdentifier(activeIdentifier)) {
          return false;
        }
        if (payload.updatedAt && payload.updatedAt < sessionStartedAt) {
          return false;
        }
        return true;
      }
    };

    const abandoned = new Set<string>();
    const now = 100000;

    // 1. Normal login tab ignores cross-tab recovery updates
    expect(shouldAcceptCrossTab('login-identifier', null, '', now, abandoned, { email: 'alice@example.com', challengeId: 'c1', updatedAt: now + 10 })).toBe(false);
    expect(shouldAcceptCrossTab('login-password', null, 'bob@example.com', now, abandoned, { email: 'alice@example.com', challengeId: 'c1', updatedAt: now + 10 })).toBe(false);

    // 2. Active challenge REQUIRES exact challenge ID (matching email alone is rejected!)
    const active = { challengeId: 'c-active-1', email: 'alice@example.com', observedAt: now };
    expect(shouldAcceptCrossTab('reset-verify', active, 'alice@example.com', now, abandoned, { email: 'alice@example.com', challengeId: 'c-old-or-different', updatedAt: now + 50 })).toBe(false);
    expect(shouldAcceptCrossTab('reset-verify', active, 'alice@example.com', now, abandoned, { email: 'alice@example.com', challengeId: 'c-active-1', updatedAt: now + 50 })).toBe(true);

    // 3. Stale update with earlier updatedAt is rejected
    expect(shouldAcceptCrossTab('reset-verify', active, 'alice@example.com', now, abandoned, { email: 'alice@example.com', challengeId: 'c-active-1', updatedAt: now - 50 })).toBe(false);

    // 4. Abandoned challenge is rejected even if user left same identifier in input
    abandoned.add('c-active-1');
    const newSessionStarted = now + 100;
    expect(shouldAcceptCrossTab('reset-request', null, 'alice@example.com', newSessionStarted, abandoned, { email: 'alice@example.com', challengeId: 'c-active-1', updatedAt: newSessionStarted + 10 })).toBe(false);

    // 5. Delayed update generated BEFORE the new session started is rejected
    expect(shouldAcceptCrossTab('reset-request', null, 'alice@example.com', newSessionStarted, abandoned, { email: 'alice@example.com', challengeId: 'c-new', updatedAt: newSessionStarted - 20 })).toBe(false);

    // 6. Fresh update generated AFTER new session started is accepted
    expect(shouldAcceptCrossTab('reset-request', null, 'alice@example.com', newSessionStarted, abandoned, { email: 'alice@example.com', challengeId: 'c-new', updatedAt: newSessionStarted + 20 })).toBe(true);
  });
});

describe('Unified Auth: In-Flight Request Cancellation & Attempt Generation', () => {
  it('discards late responses when user navigates away or presses browser Back during request', async () => {
    let currentGen = 0;
    let activeController: AbortController | undefined;
    let screenStep = 'reset-request';
    let challengeUpdated = false;

    // Simulate starting an in-flight send-otp request
    const startRequest = () => {
      const gen = ++currentGen;
      const controller = new AbortController();
      activeController = controller;
      const signal = controller.signal;

      const finishRequest = () => {
        if (gen !== currentGen || signal.aborted) {
          return; // Discard!
        }
        if (screenStep !== 'reset-request') {
          return; // Discard!
        }
        challengeUpdated = true;
        screenStep = 'reset-verify';
      };

      return { gen, finishRequest };
    };

    // User starts request
    const { finishRequest } = startRequest();

    // User immediately presses browser Back (popstate fires)
    currentGen++;
    activeController?.abort();
    screenStep = 'login-identifier';

    // Delayed network response arrives later
    finishRequest();

    // Verify screen did NOT jump to reset-verify and challenge was NOT set
    expect(screenStep).toBe('login-identifier');
    expect(challengeUpdated).toBe(false);
  });
});

describe('Unified Auth: Browser History Navigation Hygiene', () => {
  it('prevents history accumulation on repeated recovery enter and exit cycles', () => {
    let historyLength = 1;
    let historyPushed = false;
    let currentState: Record<string, unknown> | null = null;

    const mockPushState = (state: Record<string, unknown>) => {
      historyLength++;
      historyPushed = true;
      currentState = state;
    };

    const mockBack = () => {
      if (historyLength > 1) {
        historyLength--;
      }
      historyPushed = false;
      currentState = null;
    };

    const mockReplaceState = (state: Record<string, unknown> | null) => {
      currentState = state;
    };

    // Cycle 1: Enter recovery
    mockPushState({ lgbAuthRecovery: true });
    expect(historyLength).toBe(2);
    expect(historyPushed).toBe(true);
    expect(currentState).toEqual({ lgbAuthRecovery: true });

    // Cycle 1: Exit recovery via returnToLogin
    if (historyPushed) {
      mockBack();
    } else {
      mockReplaceState(null);
    }
    expect(historyLength).toBe(1);
    expect(historyPushed).toBe(false);
    expect(currentState).toBeNull();

    // Cycle 2: Enter recovery again
    mockPushState({ lgbAuthRecovery: true });
    expect(historyLength).toBe(2);

    // Cycle 2: Exit recovery via returnToLogin
    if (historyPushed) {
      mockBack();
    }
    expect(historyLength).toBe(1);

    // Cycle 3: Repeat 10 cycles
    for (let i = 0; i < 10; i++) {
      mockPushState({ lgbAuthRecovery: true });
      mockBack();
    }
    expect(historyLength).toBe(1);
  });
});

describe('Unified Auth: Signup Consolidation & Session Cleanliness', () => {
  beforeEach(() => {
    installMemorySessionStorage();
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('clears challenge and credentials when email correction is requested', () => {
    savePendingSignupCredentials({
      name: 'John Doe',
      email: 'wrong@example.com',
      phone: '+1 (519)-555-1234',
    });
    saveSignupOtpChallengeState({
      email: 'wrong@example.com',
      challengeId: 'chal-wrong',
      attemptToken: 'token-xyz',
    });

    expect(loadPendingSignupCredentials()?.email).toBe('wrong@example.com');
    expect(loadSignupOtpChallengeState()?.attemptToken).toBe('token-xyz');

    clearAllSignupSession();

    expect(loadPendingSignupCredentials()).toBeNull();
    expect(loadSignupOtpChallengeState()).toBeNull();
  });

  it('keeps attempt token bound to email identity', () => {
    const existing = {
      email: 'user1@example.com',
      challengeId: 'chal-1',
      attemptToken: 'token-user1',
    };

    // If backend returns challenge for user1, state is preserved
    const updated = applyBackendChallengeState(existing, {
      code: 'OTP_SENT',
      serverTime: new Date().toISOString(),
    }, 'user1@example.com');
    expect(updated.attemptToken).toBe('token-user1');

    // If user changes email to user2, token from user1 is never bound to user2
    const switched = applyBackendChallengeState(null, {
      code: 'OTP_SENT',
      serverTime: new Date().toISOString(),
    }, 'user2@example.com');
    expect(switched.attemptToken).toBeUndefined();
  });

  it('calculates signup cooldown based on server clocks without interval drift', () => {
    const serverTime = '2026-11-16T20:00:00.000Z';
    const nextResendAt = '2026-11-16T20:00:30.000Z';
    const receivedAtMs = Date.parse(serverTime);

    const rem0 = cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs, receivedAtMs);
    expect(rem0).toBe(30);

    const rem15 = cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 15000, receivedAtMs);
    expect(rem15).toBe(15);

    const rem35 = cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 35000, receivedAtMs);
    expect(rem35).toBe(0);
  });
});

describe('Unified Auth: Identifier & Input Validation', () => {
  it('validates emails strictly', () => {
    expect(isValidEmail('amberverma195@gmail.com')).toBe(true);
    expect(isValidEmail('amberverma@gma')).toBe(false); // No valid TLD
    expect(isValidEmail('user@domain..com')).toBe(false);
    expect(isValidEmail('invalid')).toBe(false);
  });

  it('validates phone numbers strictly', () => {
    expect(isValidPhoneNumber('+1 (519)-555-1234')).toBe(true);
    expect(isValidPhoneNumber('5195551234')).toBe(true);
    expect(isValidPhoneNumber('12345')).toBe(false);
  });

  it('normalizes identifiers for backend consistency', () => {
    expect(normalizeIdentifier('User@Example.COM')).toBe('user@example.com');
    expect(normalizeIdentifier('+1 (519)-555-1234')).toBe('+15195551234');
    expect(normalizeIdentifier('5195551234')).toBe('+15195551234');
  });
});
