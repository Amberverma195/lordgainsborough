import { describe, expect, it } from 'vitest';
import {
  createServerCooldown,
  loginErrorMessage,
  parseRetryAfterSeconds,
  readJsonObject,
  remainingServerCooldownSeconds,
  resolveRetryAfterSeconds,
} from './authSecurity';

describe('auth response security helpers', () => {
  it('parses coded JSON and safely handles non-JSON bodies', async () => {
    const coded = await readJsonObject<{ code: string; retryAfterSeconds: number }>(
      new Response(JSON.stringify({
        code: 'LOGIN_RATE_LIMITED',
        retryAfterSeconds: 42,
      }))
    );
    expect(coded).toEqual({
      code: 'LOGIN_RATE_LIMITED',
      retryAfterSeconds: 42,
    });

    const malformed = await readJsonObject(new Response('not-json'));
    expect(malformed).toEqual({});
  });

  it('parses Retry-After seconds and HTTP dates against server time', () => {
    expect(parseRetryAfterSeconds('17', '2026-07-13T12:00:00.000Z')).toBe(17);
    expect(parseRetryAfterSeconds(
      'Mon, 13 Jul 2026 12:00:25 GMT',
      '2026-07-13T12:00:00.000Z'
    )).toBe(25);
    expect(parseRetryAfterSeconds('invalid')).toBeNull();
  });

  it('prefers Retry-After over the JSON fallback', () => {
    expect(resolveRetryAfterSeconds(
      '30',
      90,
      '2026-07-13T12:00:00.000Z'
    )).toBe(30);
    expect(resolveRetryAfterSeconds(
      null,
      90,
      '2026-07-13T12:00:00.000Z'
    )).toBe(90);
  });

  it('counts down using one observed server clock anchor', () => {
    const observedAt = Date.parse('2026-07-13T08:00:00.000-04:00');
    const cooldown = createServerCooldown(
      30,
      '2026-07-13T12:00:00.000Z',
      observedAt
    );
    expect(remainingServerCooldownSeconds(cooldown, observedAt)).toBe(30);
    expect(remainingServerCooldownSeconds(cooldown, observedAt + 11_000)).toBe(19);
    expect(remainingServerCooldownSeconds(cooldown, observedAt + 31_000)).toBe(0);
  });

  it('uses generic login messages rather than reflecting backend text', () => {
    expect(loginErrorMessage(401, 'LOGIN_INVALID_CREDENTIALS'))
      .toBe('The email, phone number, or password is incorrect.');
    expect(loginErrorMessage(429, 'LOGIN_RATE_LIMITED'))
      .toContain('Too many sign-in attempts');
  });
});
