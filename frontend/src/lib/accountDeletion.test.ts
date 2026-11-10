import { afterEach, expect, it, vi } from 'vitest';
import { ACCOUNT_DELETED_KEY, clearDeletedAccountData } from './accountDeletion';
import { PENDING_SIGNUP_KEY, SIGNUP_OTP_CHALLENGE_KEY } from './signupOtp';

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    key: (index: number) => Array.from(values.keys())[index] ?? null,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
  };
}

afterEach(() => vi.unstubAllGlobals());

it.each([true, false])('removes private signup state during deletion cleanup (broadcast=%s)', broadcast => {
  const session = memoryStorage(), local = memoryStorage();
  const dispatchEvent = vi.fn();
  vi.stubGlobal('sessionStorage', session);
  vi.stubGlobal('localStorage', local);
  vi.stubGlobal('window', { sessionStorage: session, dispatchEvent });
  vi.stubGlobal('crypto', { randomUUID: () => 'deletion-notification' });
  session.setItem(PENDING_SIGNUP_KEY, JSON.stringify({ email: 'member@example.com', phone: '5195551234' }));
  session.setItem(SIGNUP_OTP_CHALLENGE_KEY, JSON.stringify({ email: 'member@example.com', attemptToken: 'private-attempt-token' }));
  session.setItem('orderTrackingToken:order-1', 'tracking-secret');
  session.setItem('passwordResetChallenge', 'private-reset-state');
  session.setItem('unrelatedPreference', 'keep');
  local.setItem('sessionUser', 'member@example.com');

  // Both the deleting tab and notified tabs execute this same cleanup function.
  clearDeletedAccountData(broadcast);

  expect(session.getItem(PENDING_SIGNUP_KEY)).toBeNull();
  expect(session.getItem(SIGNUP_OTP_CHALLENGE_KEY)).toBeNull();
  expect(session.getItem('orderTrackingToken:order-1')).toBeNull();
  expect(session.getItem('passwordResetChallenge')).toBeNull();
  expect(session.getItem('unrelatedPreference')).toBe('keep');
  expect(local.getItem('sessionUser')).toBeNull();
  expect(local.getItem(ACCOUNT_DELETED_KEY)).toBe(broadcast ? 'deletion-notification' : null);
  expect(dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'storage' }));
});
