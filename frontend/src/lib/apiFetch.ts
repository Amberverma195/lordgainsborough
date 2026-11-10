import { sessionCookieConfig } from './sessionConfig';

function readCsrfCookie(): string | undefined {
  const name = sessionCookieConfig(new URL(window.location.href)).csrf;
  return document.cookie.split('; ').find(cookie => cookie.startsWith(`${name}=`))?.slice(name.length + 1);
}

/** Browser API calls use same-origin cookies. JWTs never enter browser JavaScript. */
export async function apiFetch(input: string | URL, init: RequestInit = {}): Promise<Response> {
  if (typeof window === 'undefined') return fetch(input, init);
  const url = new URL(input, window.location.origin);
  const sameOrigin = url.origin === window.location.origin;
  const headers = new Headers(init.headers);
  headers.delete('Authorization');
  if (sameOrigin && !['GET', 'HEAD', 'OPTIONS'].includes((init.method || 'GET').toUpperCase())) {
    let csrf = readCsrfCookie();
    if (!csrf) {
      const response = await fetch('/api/session/csrf', { credentials: 'same-origin', cache: 'no-store' });
      if (!response.ok) throw new Error('Unable to establish request protection');
      csrf = (await response.json()).csrfToken;
    }
    if (!csrf) throw new Error('Missing request protection');
    headers.set('X-CSRF-Token', csrf);
  }
  return fetch(input, { ...init, headers, credentials: sameOrigin ? 'same-origin' : 'omit' });
}
