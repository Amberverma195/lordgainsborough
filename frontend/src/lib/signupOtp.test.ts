import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyBackendChallengeState,
  cooldownSecondsFromServerClocks,
  fetchWithIdempotentRetry,
  isChallengeExpiredState,
  isResendExhausted,
  loadPendingSignupCredentials,
  loadSignupOtpChallengeState,
  PENDING_SIGNUP_KEY,
  savePendingSignupCredentials,
  saveSignupOtpChallengeState,
  SIGNUP_OTP_CHALLENGE_KEY,
  SignupOtpChallengeState,
} from './signupOtp';

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
  };
  vi.stubGlobal('sessionStorage', memory);
  return memory;
}

describe('signupOtp challenge state', () => {
  beforeEach(() => {
    installMemorySessionStorage();
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('refresh during cooldown uses server clocks instead of a fresh 30s timer', () => {
    const serverTime = '2026-07-13T12:00:05.000Z';
    const nextResendAt = '2026-07-13T12:00:20.000Z';
    const clientNow = Date.parse('2026-07-13T12:00:05.000Z');

    const seconds = cooldownSecondsFromServerClocks(serverTime, nextResendAt, clientNow);
    expect(seconds).toBe(15);
    expect(seconds).not.toBe(30);
  });

  it('countdown decrements once per second with receivedAtMs baseline and reaches 0', () => {
    const serverTime = '2026-07-13T12:00:00.000Z';
    const nextResendAt = '2026-07-13T12:00:30.000Z';
    const receivedAtMs = 1000000;

    // Progression from 30 -> 29 -> 28
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs, receivedAtMs)).toBe(30);
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 1000, receivedAtMs)).toBe(29);
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 2000, receivedAtMs)).toBe(28);
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 15000, receivedAtMs)).toBe(15);
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 29000, receivedAtMs)).toBe(1);
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 30000, receivedAtMs)).toBe(0);
    expect(cooldownSecondsFromServerClocks(serverTime, nextResendAt, receivedAtMs + 31000, receivedAtMs)).toBe(0);
  });

  it('reloading page or resuming background tab recovers cooldown naturally from saved state', () => {
    const clientReceiptTime = 1720872000000; // local Date.now()
    const state = applyBackendChallengeState(null, {
      code: 'OTP_SENT',
      challengeId: 'c-test',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      resendsRemaining: 5,
    }, 'amber@example.com', clientReceiptTime);

    saveSignupOtpChallengeState(state);
    const reloaded = loadSignupOtpChallengeState();
    expect(reloaded).not.toBeNull();
    expect(reloaded?.receivedAtMs).toBe(clientReceiptTime);

    // 12 seconds elapsed in background tab / reload
    const nowAfter12s = clientReceiptTime + 12000;
    const remaining = cooldownSecondsFromServerClocks(
      reloaded?.serverTime,
      reloaded?.nextResendAt,
      nowAfter12s,
      reloaded?.receivedAtMs
    );
    expect(remaining).toBe(18);
  });

  it('challenge expiry advances naturally with elapsed time', () => {
    const receivedAtMs = 1720872000000;
    const state: SignupOtpChallengeState = {
      email: 'test@example.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:00.000Z',
      challengeExpiresAt: '2026-07-13T12:10:00.000Z', // 10 minutes TTL
      receivedAtMs,
    };

    // 5 minutes in: not expired
    expect(isChallengeExpiredState(state, receivedAtMs + 300000)).toBe(false);
    // 9 minutes 59 seconds in: not expired
    expect(isChallengeExpiredState(state, receivedAtMs + 599000)).toBe(false);
    // 10 minutes in: expired!
    expect(isChallengeExpiredState(state, receivedAtMs + 600000)).toBe(true);
    // 11 minutes in: expired!
    expect(isChallengeExpiredState(state, receivedAtMs + 660000)).toBe(true);
  });

  it('stale local countdown is overridden by backend response', () => {
    const stale: SignupOtpChallengeState = {
      email: 'member@example.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      resendsRemaining: 4,
      code: 'OTP_SENT',
    };

    const fresh = applyBackendChallengeState(stale, {
      code: 'OTP_RESEND_COOLDOWN',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:25.000Z',
      nextResendAt: '2026-07-13T12:00:40.000Z',
      resendsRemaining: 4,
      message: 'Please wait',
    });

    expect(fresh.serverTime).toBe('2026-07-13T12:00:25.000Z');
    expect(fresh.nextResendAt).toBe('2026-07-13T12:00:40.000Z');
    expect(cooldownSecondsFromServerClocks(
      fresh.serverTime,
      fresh.nextResendAt,
      Date.parse('2026-07-13T12:00:25.000Z')
    )).toBe(15);
  });

  it('lost HTTP response retry reuses the same idempotency key', async () => {
    const keys: string[] = [];
    let attempts = 0;

    const res = await fetchWithIdempotentRetry(async (key) => {
      keys.push(key);
      attempts += 1;
      if (attempts === 1) {
        throw new TypeError('network lost');
      }
      return new Response(JSON.stringify({ code: 'OTP_SENT' }), { status: 202 });
    }, 'fixed-click-key');

    expect(res.status).toBe(202);
    expect(keys).toEqual(['fixed-click-key', 'fixed-click-key']);
  });

  it('exhausted quota is detected from backend code or remaining count', () => {
    expect(isResendExhausted({
      email: 'a@b.com',
      code: 'OTP_RESEND_LIMIT_REACHED',
      resendsRemaining: 1,
    })).toBe(true);

    expect(isResendExhausted({
      email: 'a@b.com',
      code: 'OTP_ALREADY_ACTIVE',
      resendsRemaining: 0,
    })).toBe(true);

    expect(isResendExhausted({
      email: 'a@b.com',
      code: 'OTP_ALREADY_ACTIVE',
      resendsRemaining: 2,
    })).toBe(false);
  });

  it('expired challenge is detected from code or challengeExpiresAt', () => {
    expect(isChallengeExpiredState({
      email: 'a@b.com',
      code: 'OTP_CHALLENGE_EXPIRED',
    })).toBe(true);

    const now = Date.parse('2026-07-13T12:30:00.000Z');
    expect(isChallengeExpiredState({
      email: 'a@b.com',
      code: 'OTP_ALREADY_ACTIVE',
      serverTime: '2026-07-13T12:30:00.000Z',
      challengeExpiresAt: '2026-07-13T12:29:00.000Z',
    }, now)).toBe(true);

    expect(isChallengeExpiredState({
      email: 'a@b.com',
      code: 'OTP_ALREADY_ACTIVE',
      serverTime: '2026-07-13T12:00:00.000Z',
      challengeExpiresAt: '2026-07-13T12:30:00.000Z',
    }, Date.parse('2026-07-13T12:00:00.000Z'))).toBe(false);
  });

  it('never persists otp digits in challenge session state', () => {
    saveSignupOtpChallengeState({
      email: 'member@example.com',
      challengeId: 'c1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      resendsRemaining: 5,
    });

    const raw = sessionStorage.getItem(SIGNUP_OTP_CHALLENGE_KEY) || '';
    expect(raw.toLowerCase()).not.toContain('otp');
    expect(raw).not.toMatch(/\d{6}/);

    // Even if a buggy payload included otp, load strips it.
    sessionStorage.setItem(SIGNUP_OTP_CHALLENGE_KEY, JSON.stringify({
      email: 'member@example.com',
      challengeId: 'c1',
      otp: '123456',
    }));
    const loaded = loadSignupOtpChallengeState();
    expect(loaded).not.toBeNull();
    expect(loaded).not.toHaveProperty('otp');
  });

  it('never persists a pending signup password and migrates legacy values', () => {
    savePendingSignupCredentials({
      name: 'Member Name',
      email: 'member@example.com',
      phone: '+1 (416)-555-0100',
      password: 'Plaintext1!',
    } as Parameters<typeof savePendingSignupCredentials>[0] & { password: string });

    let raw = sessionStorage.getItem(PENDING_SIGNUP_KEY) ?? '';
    expect(raw).not.toContain('Plaintext1!');
    expect(raw).not.toContain('"password"');

    sessionStorage.setItem(PENDING_SIGNUP_KEY, JSON.stringify({
      name: 'Legacy Member',
      email: 'legacy@example.com',
      phone: '+1 (416)-555-0101',
      password: 'LegacyPlaintext1!',
    }));
    expect(loadPendingSignupCredentials()).toEqual({
      name: 'Legacy Member',
      email: 'legacy@example.com',
      phone: '+1 (416)-555-0101',
    });
    raw = sessionStorage.getItem(PENDING_SIGNUP_KEY) ?? '';
    expect(raw).not.toContain('LegacyPlaintext1!');
    expect(raw).not.toContain('"password"');
  });

  it('missing timing metadata does not invent a cooldown', () => {
    expect(cooldownSecondsFromServerClocks(undefined, undefined)).toBe(0);
    expect(cooldownSecondsFromServerClocks('2026-07-13T12:00:00.000Z', undefined)).toBe(0);
  });

  it('applyBackendChallengeState stores and preserves attemptToken', () => {
    const initial = applyBackendChallengeState(null, {
      code: 'OTP_SENT',
      challengeId: 'c-attempt-1',
      attemptToken: 'attempt-token-secret-123',
      serverTime: '2026-07-13T12:00:00.000Z',
    }, 'applicant@example.com');

    expect(initial.attemptToken).toBe('attempt-token-secret-123');

    // Subsequent status update without attemptToken preserves the established attemptToken
    const updated = applyBackendChallengeState(initial, {
      code: 'OTP_ALREADY_ACTIVE',
      challengeId: 'c-attempt-1',
      serverTime: '2026-07-13T12:00:10.000Z',
    });

    expect(updated.attemptToken).toBe('attempt-token-secret-123');

    // Safe restart with new attemptToken updates the attemptToken
    const restarted = applyBackendChallengeState(updated, {
      code: 'OTP_SENT',
      challengeId: 'c-attempt-2',
      attemptToken: 'attempt-token-secret-456',
      serverTime: '2026-07-13T12:01:00.000Z',
    });

    expect(restarted.attemptToken).toBe('attempt-token-secret-456');
    expect(restarted.challengeId).toBe('c-attempt-2');
  });

  it('saveSignupOtpChallengeState persists attemptToken and loadSignupOtpChallengeState recovers it', () => {
    const state: SignupOtpChallengeState = {
      email: 'applicant@example.com',
      challengeId: 'c-attempt-1',
      attemptToken: 'secret-token-xyz',
      serverTime: '2026-07-13T12:00:00.000Z',
    };

    saveSignupOtpChallengeState(state);
    const loaded = loadSignupOtpChallengeState();
    expect(loaded).not.toBeNull();
    expect(loaded?.attemptToken).toBe('secret-token-xyz');
    expect(loaded?.email).toBe('applicant@example.com');
  });
});
