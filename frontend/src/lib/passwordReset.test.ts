import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyPasswordResetResponse,
  clearPasswordResetChallengeState,
  fetchPasswordResetWithRetry,
  isPasswordResetChallengeExpired,
  isPasswordResetResendExhausted,
  loadPasswordResetChallengeState,
  PASSWORD_RESET_CHALLENGE_KEY,
  passwordResetCooldownSeconds,
  readPasswordResetResponse,
  savePasswordResetChallengeState,
  toSafePasswordResetState,
} from './passwordReset';

function installMemorySessionStorage() {
  const store = new Map<string, string>();
  const storage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => store.set(key, String(value)),
    removeItem: (key: string) => store.delete(key),
    clear: () => store.clear(),
  };
  vi.stubGlobal('sessionStorage', storage);
  return storage;
}

describe('password reset helpers', () => {
  beforeEach(() => {
    installMemorySessionStorage();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('parses the coded password reset JSON contract', async () => {
    const body = await readPasswordResetResponse(new Response(JSON.stringify({
      code: 'RESET_CODE_SENT',
      challengeId: 'challenge-1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      resendsRemaining: 4,
    })));
    expect(body.code).toBe('RESET_CODE_SENT');
    expect(body.challengeId).toBe('challenge-1');
    expect(body.resendsRemaining).toBe(4);
  });

  it('preserves server clock skew while counting down after refresh', () => {
    const observedAt = Date.parse('2026-07-13T07:00:00.000-04:00');
    const state = applyPasswordResetResponse(null, 'MEMBER@example.com', {
      challengeId: 'challenge-1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
    }, 30, observedAt);

    expect(passwordResetCooldownSeconds(state, observedAt)).toBe(30);
    expect(passwordResetCooldownSeconds(state, observedAt + 12_000)).toBe(18);
    expect(passwordResetCooldownSeconds(state, observedAt + 31_000)).toBe(0);
  });

  it('persists only allow-listed safe challenge metadata', () => {
    const unsafe = {
      email: 'member@example.com',
      challengeId: 'challenge-1',
      serverTime: '2026-07-13T12:00:00.000Z',
      observedAt: Date.parse('2026-07-13T12:00:00.000Z'),
      otp: '123456',
      password: 'Plaintext1!',
      token: 'secret',
      message: 'provider detail',
      code: 'RESET_CODE_SENT',
    };
    const safe = toSafePasswordResetState(unsafe);
    expect(safe).not.toBeNull();
    savePasswordResetChallengeState(safe!);

    const raw = sessionStorage.getItem(PASSWORD_RESET_CHALLENGE_KEY) ?? '';
    expect(raw).not.toContain('123456');
    expect(raw).not.toContain('Plaintext1!');
    expect(raw).not.toContain('"otp"');
    expect(raw).not.toContain('"password"');
    expect(raw).not.toContain('"token"');
    expect(raw).not.toContain('"message"');
    expect(raw).not.toContain('"code"');
  });

  it('strips unsafe legacy fields while loading session metadata', () => {
    sessionStorage.setItem(PASSWORD_RESET_CHALLENGE_KEY, JSON.stringify({
      email: 'member@example.com',
      challengeId: 'challenge-1',
      observedAt: 100,
      otp: '123456',
      password: 'Plaintext1!',
    }));
    const loaded = loadPasswordResetChallengeState();
    expect(loaded).toEqual({
      email: 'member@example.com',
      challengeId: 'challenge-1',
      observedAt: 100,
      serverTime: undefined,
      nextResendAt: undefined,
      codeExpiresAt: undefined,
      challengeExpiresAt: undefined,
      resendsRemaining: undefined,
      retryAfterSeconds: undefined,
    });
    expect(loaded).not.toHaveProperty('otp');
    expect(loaded).not.toHaveProperty('password');
  });

  it('reuses one deliberate-action idempotency key only for transport retry', async () => {
    const keys: string[] = [];
    const response = await fetchPasswordResetWithRetry(async (key) => {
      keys.push(key);
      if (keys.length === 1) throw new TypeError('connection lost');
      return new Response('{}', { status: 202 });
    }, 'one-click-key');

    expect(response.status).toBe(202);
    expect(keys).toEqual(['one-click-key', 'one-click-key']);
  });

  it('evaluates challenge expiry taking clock skew into account', () => {
    const observedAt = Date.parse('2026-07-13T07:00:00.000-04:00'); // 11:00 UTC
    const serverTime = '2026-07-13T12:00:00.000Z'; // Server says 12:00 UTC (client is 1h behind)
    const challengeExpiresAt = '2026-07-13T12:30:00.000Z'; // 30 minutes after server time

    const state = applyPasswordResetResponse(null, 'member@example.com', {
      challengeId: 'challenge-skew',
      serverTime,
      challengeExpiresAt,
      resendsRemaining: 3,
    }, 0, observedAt);

    expect(state).not.toBeNull();
    // At observedAt (server time 12:00 UTC), not expired
    expect(isPasswordResetChallengeExpired(state, observedAt)).toBe(false);
    // 29 minutes later in client time (server time 12:29 UTC), not expired
    expect(isPasswordResetChallengeExpired(state, observedAt + 29 * 60 * 1000)).toBe(false);
    // 30 minutes and 1 second later (server time 12:30:01 UTC), expired!
    expect(isPasswordResetChallengeExpired(state, observedAt + 30 * 60 * 1000 + 1000)).toBe(true);
  });

  it('detects resend exhaustion and clears challenge state cleanly', () => {
    const state = applyPasswordResetResponse(null, 'member@example.com', {
      challengeId: 'challenge-exhaust',
      serverTime: '2026-07-13T12:00:00.000Z',
      resendsRemaining: 0,
    }, 0, 1000);

    expect(isPasswordResetResendExhausted(state)).toBe(true);

    savePasswordResetChallengeState(state!);
    expect(sessionStorage.getItem(PASSWORD_RESET_CHALLENGE_KEY)).not.toBeNull();

    clearPasswordResetChallengeState();
    expect(sessionStorage.getItem(PASSWORD_RESET_CHALLENGE_KEY)).toBeNull();
    expect(loadPasswordResetChallengeState()).toBeNull();
  });

  it('immediately marks challenge expired when server responds with RESET_CHALLENGE_EXPIRED', () => {
    const observedAt = Date.parse('2026-07-13T12:00:00.000Z');
    // Initially active challenge expiring 30m in future
    const activeState = applyPasswordResetResponse(null, 'member@example.com', {
      challengeId: 'challenge-exp-test',
      serverTime: '2026-07-13T12:00:00.000Z',
      challengeExpiresAt: '2026-07-13T12:30:00.000Z',
      resendsRemaining: 3,
    }, 0, observedAt);

    expect(isPasswordResetChallengeExpired(activeState, observedAt)).toBe(false);

    // Backend returns RESET_CHALLENGE_EXPIRED (e.g. 410 Gone)
    const expiredState = applyPasswordResetResponse(activeState, 'member@example.com', {
      code: 'RESET_CHALLENGE_EXPIRED',
      message: 'This password-reset session has expired.',
      serverTime: '2026-07-13T12:05:00.000Z',
    }, 0, observedAt + 5 * 60 * 1000);

    expect(expiredState).not.toBeNull();
    // Must immediately evaluate to expired without waiting for original 30m countdown
    expect(isPasswordResetChallengeExpired(expiredState, observedAt + 5 * 60 * 1000)).toBe(true);
  });

  it('handles RESET_CHALLENGE_REUSED response code and preserves challenge metadata', () => {
    const observedAt = Date.parse('2026-07-13T12:00:00.000Z');
    const state = applyPasswordResetResponse(null, 'member@example.com', {
      code: 'RESET_CHALLENGE_REUSED',
      message: 'If an account exists for that email, a reset code was already sent.',
      challengeId: 'challenge-reused-1',
      serverTime: '2026-07-13T12:00:00.000Z',
      nextResendAt: '2026-07-13T12:00:30.000Z',
      resendsRemaining: 5,
    }, 0, observedAt);

    expect(state).not.toBeNull();
    expect(state?.challengeId).toBe('challenge-reused-1');
    expect(state?.resendsRemaining).toBe(5);
  });
});
