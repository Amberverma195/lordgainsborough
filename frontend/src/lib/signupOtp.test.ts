import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyBackendChallengeState,
  cooldownSecondsFromServerClocks,
  fetchWithIdempotentRetry,
  isChallengeExpiredState,
  isResendExhausted,
  loadSignupOtpChallengeState,
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

  it('missing timing metadata does not invent a cooldown', () => {
    expect(cooldownSecondsFromServerClocks(undefined, undefined)).toBe(0);
    expect(cooldownSecondsFromServerClocks('2026-07-13T12:00:00.000Z', undefined)).toBe(0);
  });
});
