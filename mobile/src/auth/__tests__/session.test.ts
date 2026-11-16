import { ApiError } from '../../api/errors';
import { loginFailureMessage, shouldClearStoredSession } from '../session';

describe('session handling', () => {
  it('clears a stored token only when that token was rejected', () => {
    const expired = new ApiError({ status: 401, code: 'INVALID_TOKEN', message: 'Your session is invalid or expired. Please sign in again.' });
    expect(shouldClearStoredSession(expired, true)).toBe(true);
    expect(shouldClearStoredSession(expired, false)).toBe(false);
    expect(shouldClearStoredSession(new ApiError({
      status: 401,
      code: 'LOGIN_INVALID_CREDENTIALS',
      message: 'Invalid email, phone number, or password.',
    }), false)).toBe(false);
  });

  it('keeps the token when the restaurant cannot be reached', () => {
    expect(shouldClearStoredSession(new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'offline',
    }), true)).toBe(false);
  });

  it('includes the server retry delay for a limited login', () => {
    expect(loginFailureMessage(new ApiError({
      status: 429,
      code: 'LOGIN_RATE_LIMITED',
      message: 'Too many login attempts. Please try again later.',
      retryAfterSeconds: 30,
    }))).toContain('30 seconds');
  });
});
