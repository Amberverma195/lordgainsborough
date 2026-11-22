import { disableOrderAlerts } from '../notifications/orderAlerts';
import { logout } from '../api/auth';
import { readSessionToken } from './secureSession';
export async function revokeAndClearSession(
  clearLocalSession: () => Promise<void>,
  accountEmail?: string,
) {
  const token = await readSessionToken(true);
  if (token) {
    if (accountEmail) await disableOrderAlerts(accountEmail, token);
    await logout(token);
    // A late logout response must not clear a different account's new session.
    if ((await readSessionToken(true)) !== token) return;
  }
  await clearLocalSession();
}
