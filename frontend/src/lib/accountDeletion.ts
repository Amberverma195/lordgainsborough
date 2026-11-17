import { clearAuth } from './auth';
import { clearCheckoutIdempotencyKey } from './orders';
import { clearPickupSelections } from './pickupSelection';
import { clearAllSignupSession } from './signupOtp';
import { clearAccountCart, clearGuestCart, purgeLegacyGlobalCart } from './cartStorage';

export const ACCOUNT_DELETED_KEY = 'accountDeleted';

/** Clear this tab's private data. The marker also notifies other open tabs. */
export function clearDeletedAccountData(broadcast = true) {
  const currentEmail = typeof window !== 'undefined' ? localStorage.getItem('sessionUser') : null;
  if (currentEmail) {
    clearAccountCart(currentEmail);
  }
  clearAuth();
  purgeLegacyGlobalCart();
  clearGuestCart();
  try {
    clearCheckoutIdempotencyKey(currentEmail);
    clearPickupSelections();
    clearAllSignupSession();
    const keys = Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index));
    for (const key of keys) {
      if (key?.startsWith('orderTrackingToken:') || key === 'passwordResetChallenge') sessionStorage.removeItem(key);
    }
  } catch { /* Storage may be unavailable; the server has already revoked access. */ }
  if (broadcast) localStorage.setItem(ACCOUNT_DELETED_KEY, crypto.randomUUID());
  window.dispatchEvent(new Event('storage'));
}
