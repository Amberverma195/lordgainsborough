import { apiFetch } from './apiFetch';
import { KDS_API_BASE } from './orders';

/** A 60-second ticket authorizes the KDS socket room without exposing account JWTs. */
export function kdsSocketAuth() {
  return async (callback: (auth: object) => void) => {
    try {
      const response = await apiFetch(`${KDS_API_BASE}/kds/socket-ticket`, { cache: 'no-store' });
      if (!response.ok) {
        callback({});
        return;
      }
      const data = await response.json();
      callback({ token: data.ticket });
    } catch {
      callback({});
    }
  };
}
