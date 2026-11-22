import { apiFetch } from './apiFetch';
import { KDS_API_BASE } from './orders';

export interface KdsSocketAuthPayload {
  token?: string | null;
  error?: string;
}

/**
 * A 60-second ticket authorizes the KDS socket room without exposing account JWTs.
 * Distinguishes ticket retrieval failures with safe diagnostic logs without logging secrets.
 */
export function kdsSocketAuth() {
  return async (callback: (auth: KdsSocketAuthPayload) => void) => {
    try {
      const response = await apiFetch(`${KDS_API_BASE}/kds/socket-ticket`, { cache: 'no-store' });
      if (!response.ok) {
        const errorType =
          response.status === 401 || response.status === 403 || response.status === 404
            ? `AUTH_FAILED_${response.status}`
            : response.status >= 500
            ? `SERVER_ERROR_${response.status}`
            : `HTTP_${response.status}`;
        console.warn(`[KDS Socket] Ticket retrieval failed: ${errorType}`);
        callback({ token: null, error: errorType });
        return;
      }
      const data = await response.json();
      if (!data?.ticket || typeof data.ticket !== 'string') {
        console.warn('[KDS Socket] Ticket response missing valid ticket payload');
        callback({ token: null, error: 'MISSING_TICKET' });
        return;
      }
      callback({ token: data.ticket });
    } catch {
      console.warn('[KDS Socket] Ticket retrieval network/server failure');
      callback({ token: null, error: 'NETWORK_ERROR' });
    }
  };
}
