import { apiFetch } from './apiFetch';
import { purgeLegacyGlobalCart } from './cartStorage';

export interface AuthData {
  name?: string;
  email?: string;
  phone?: string;
  memberSince?: string;
  role?: string;
  noShowCount?: number;
  pickupDisabled?: boolean;
}

export const PICKUP_DISABLED_NO_SHOW_MESSAGE =
  'Pickup disabled due to recent no-show history';

export function persistAuth(data: AuthData) {
  localStorage.removeItem('authToken'); // Scrub tokens left by the previous browser-storage implementation.
  if (data.email) localStorage.setItem('sessionUser', data.email);
  if (data.name) {
    localStorage.setItem('userName', data.name);
  }
  if (data.email) {
    localStorage.setItem('userEmail', data.email);
  }
  if (data.phone !== undefined) {
    localStorage.setItem('userPhone', data.phone);
  }
  if (data.memberSince) {
    localStorage.setItem('userMemberSince', data.memberSince);
  }
  if (data.role) {
    localStorage.setItem('userRole', data.role);
  }
  if (data.noShowCount !== undefined) {
    localStorage.setItem('userNoShowCount', String(data.noShowCount));
  }
  if (data.pickupDisabled !== undefined) {
    localStorage.setItem('userPickupDisabled', data.pickupDisabled ? 'true' : 'false');
  }
}

export function clearAuth() {
  localStorage.removeItem('authToken');
  localStorage.removeItem('sessionUser');
  localStorage.removeItem('userName');
  localStorage.removeItem('userEmail');
  localStorage.removeItem('userPhone');
  localStorage.removeItem('userMemberSince');
  localStorage.removeItem('userRole');
  localStorage.removeItem('userNoShowCount');
  localStorage.removeItem('userPickupDisabled');
  purgeLegacyGlobalCart();
}

export type SessionValidationResult =
  | { authenticated: true; user: AuthData; reason?: undefined; status?: number }
  | { authenticated: false; reason: 'unauthorized' | 'network_error'; status?: number; error?: unknown };

export async function validateServerSession(): Promise<SessionValidationResult> {
  if (typeof window === 'undefined') {
    return { authenticated: false, reason: 'unauthorized' };
  }

  try {
    const res = await apiFetch('/api/auth/me', {
      cache: 'no-store',
    });

    if (res.status === 200) {
      const data = await res.json();
      persistAuth(data);
      return { authenticated: true, user: data };
    }

    if (res.status === 401 || res.status === 403) {
      clearAuth();
      return { authenticated: false, reason: 'unauthorized', status: res.status };
    }

    return { authenticated: false, reason: 'network_error', status: res.status };
  } catch (error) {
    return { authenticated: false, reason: 'network_error', error };
  }
}

export async function signOut() {
  try {
    await apiFetch('/api/auth/logout', { method: 'POST' });
  } catch (err) {
    console.warn('Unable to reach sign out endpoint:', err);
  } finally {
    clearAuth();
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new Event('storage'));
    }
  }
}

export function isPickupDisabledForUser(): boolean {
  if (typeof window === 'undefined') return false;
  return localStorage.getItem('userPickupDisabled') === 'true';
}

export function getAuthHeaders(includeJson = true): HeadersInit {
  const headers: Record<string, string> = {};
  if (includeJson) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
}

export function getAuthHeadersOnly(): HeadersInit {
  return {};
}

export function isAuthenticated(): boolean {
  if (typeof window === 'undefined') return false;
  return !!localStorage.getItem('sessionUser'); // UI hint only; the server validates the HttpOnly cookie.
}
