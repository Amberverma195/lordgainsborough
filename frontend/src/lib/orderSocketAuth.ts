import { apiFetch } from './apiFetch';

/** A 60-second ticket authorizes only this order's socket room, never account APIs. */
export function orderSocketAuth(orderId: string, trackingToken?: string | null) {
  return async (callback: (auth: object) => void) => {
    try {
      const headers: Record<string, string> = {};
      if (trackingToken) headers['X-Order-Tracking-Token'] = trackingToken;
      const response = await apiFetch(`/api/orders/${encodeURIComponent(orderId)}/socket-ticket`, { headers, cache: 'no-store' });
      if (!response.ok) { callback({}); return; }
      const data = await response.json();
      callback({ token: data.ticket });
    } catch { callback({}); }
  };
}
