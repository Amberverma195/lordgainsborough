import { clearAuth } from './auth';
import { clearCheckoutIdempotencyKey } from './orders';
import { clearPickupSelections } from './pickupSelection';

export const ACCOUNT_DELETED_KEY = 'accountDeleted';

/** Clear this tab's private data. The marker also notifies other open tabs. */
export function clearDeletedAccountData(broadcast = true) {
  clearAuth();
  localStorage.removeItem('lord_gainsborough_cart');
  try {
    clearCheckoutIdempotencyKey();
    clearPickupSelections();
    const keys = Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index));
    for (const key of keys) {
      if (key?.startsWith('orderTrackingToken:') || key === 'passwordResetChallenge') sessionStorage.removeItem(key);
    }
  } catch { /* Storage may be unavailable; the server has already revoked access. */ }
  if (broadcast) localStorage.setItem(ACCOUNT_DELETED_KEY, crypto.randomUUID());
  window.dispatchEvent(new Event('storage'));
}
