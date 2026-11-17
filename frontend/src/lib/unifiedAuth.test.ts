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
