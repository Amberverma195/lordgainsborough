import { ApiError } from '../api/errors';

const EXPIRED_CODES = new Set(['INVALID_TOKEN', 'UNAUTHORIZED']);

export function shouldClearStoredSession(error: unknown, hadToken: boolean): boolean {
  if (!hadToken || !(error instanceof ApiError) || error.status !== 401) {
    return false;
  }
  return EXPIRED_CODES.has(error.code) || /invalid|expired/i.test(error.message);
}

export function loginFailureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'We could not reach the restaurant. Check your connection and try again.';
  }
  if (error.code === 'LOGIN_RATE_LIMITED' && error.retryAfterSeconds) {
    return `${error.message} Try again in ${error.retryAfterSeconds} seconds.`;
  }
  return error.message;
}
