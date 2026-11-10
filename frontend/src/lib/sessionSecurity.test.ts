import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { POST, GET, DELETE } from '../app/api/[...path]/route';
import { validCsrf } from './server/sessionSecurity';
import { sessionCookieConfig } from './sessionConfig';
import { persistAuth, clearAuth } from './auth';

const csrf = 'a'.repeat(64);
const makeRequest = (path: string, method = 'POST', headers: Record<string, string> = {}) => new NextRequest(`https://restaurant.example/api/${path}`, {
  method, headers: { Origin: 'https://restaurant.example', Cookie: `__Host-lgb_csrf=${csrf}`, 'X-CSRF-Token': csrf,
    'Content-Type': 'application/json', ...headers }, ...(method === 'GET' ? {} : { body: '{}' }),
});
const context = (path: string) => ({ params: Promise.resolve({ path: path.split('/') }) });
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe('cookie session boundary', () => {
  it('sets Secure HttpOnly host cookies and strips the JWT from login responses', async () => {
    const token = `header.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now()/1000)+3600 })).toString('base64url')}.signature`;
    const upstream = vi.fn().mockResolvedValue(Response.json({ token, email: 'member@example.com', role: 'MEMBER' }));
    vi.stubGlobal('fetch', upstream);
    const response = await POST(makeRequest('auth/login'), context('auth/login'));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ email: 'member@example.com', role: 'MEMBER' });
    const cookie = response.cookies.get('__Host-lgb_session');
    expect(cookie).toMatchObject({ value: token, httpOnly: true, secure: true, sameSite: 'lax', path: '/' });
    expect(response.headers.get('cache-control')).toBe('no-store');
  });

  it('rejects missing, mismatched, and cross-origin CSRF before contacting the backend', async () => {
    const upstream = vi.fn(); vi.stubGlobal('fetch', upstream);
    const invalidHeaders: Record<string, string>[] = [{ 'X-CSRF-Token': '' }, { 'X-CSRF-Token': 'b'.repeat(64) },
      { Origin: 'https://attacker.example' }, { 'Sec-Fetch-Site': 'cross-site' }];
    for (const headers of invalidHeaders) {
      const response = await POST(makeRequest('orders', 'POST', headers), context('orders'));
      expect(response.status).toBe(403);
    }
    expect(upstream).not.toHaveBeenCalled();
    expect(validCsrf(makeRequest('orders'))).toBe(true);
  });

  it('uses only the HttpOnly cookie for upstream authentication and never trusts browser bearer headers', async () => {
    const upstream = vi.fn().mockResolvedValue(Response.json([])); vi.stubGlobal('fetch', upstream);
    await GET(makeRequest('auth/orders', 'GET', { Cookie: '__Host-lgb_session=private-session', Authorization: 'Bearer forged' }), context('auth/orders'));
    expect(upstream.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer private-session');
    upstream.mockResolvedValue(Response.json([]));
    await GET(makeRequest('auth/orders', 'GET', { Authorization: 'Bearer forged' }), context('auth/orders'));
    expect(upstream.mock.calls[1][1].headers.has('Authorization')).toBe(false);
  });

  it('expires cookies on logout and successful account deletion', async () => {
    const upstream = vi.fn().mockResolvedValue(new Response(null, { status: 204 })); vi.stubGlobal('fetch', upstream);
    const logout = await POST(makeRequest('auth/logout'), context('auth/logout'));
    expect(logout.cookies.get('__Host-lgb_session')?.maxAge).toBe(0);
    expect(upstream).not.toHaveBeenCalled();
    const deletion = await DELETE(makeRequest('auth/me', 'DELETE'), context('auth/me'));
    expect(deletion.cookies.get('__Host-lgb_session')?.maxAge).toBe(0);
  });

  it('does not let the frontend server bypass the kitchen IP allowlist', async () => {
    const upstream = vi.fn(); vi.stubGlobal('fetch', upstream);
    const response = await GET(makeRequest('kds/access', 'GET'), context('kds/access'));
    expect(response.status).toBe(404);
    expect(upstream).not.toHaveBeenCalled();
  });

  it('rejects encoded path traversal and oversized message bodies before proxying', async () => {
    const upstream = vi.fn(); vi.stubGlobal('fetch', upstream);
    const traversal = await GET(makeRequest('orders/example', 'GET'), context('orders/%2e%2e'));
    expect(traversal.status).toBe(404);
    const request = new NextRequest('https://restaurant.example/api/orders/example/messages', {
      method: 'POST', headers: { Origin: 'https://restaurant.example', Cookie: `__Host-lgb_csrf=${csrf}`,
        'X-CSRF-Token': csrf, 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: 'hello', padding: 'x'.repeat(16384) }),
    });
    expect((await POST(request, context('orders/example/messages'))).status).toBe(413);
    expect(upstream).not.toHaveBeenCalled();
  });

  it('keeps browser storage limited to display hints and removes legacy JWTs', () => {
    const storage = new Map<string, string>([['authToken', 'legacy-jwt']]);
    vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) || null,
      setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) });
    persistAuth({ email: 'member@example.com', role: 'MEMBER' });
    expect(storage.has('authToken')).toBe(false);
    expect(storage.get('sessionUser')).toBe('member@example.com');
    clearAuth();
    expect(storage.has('sessionUser')).toBe(false);
  });

  it('restricts insecure cookie exceptions to local loopback development', () => {
    expect(sessionCookieConfig(new URL('http://localhost:3000')).secure).toBe(false);
    expect(sessionCookieConfig(new URL('http://restaurant.example')).secure).toBe(true);
    expect(sessionCookieConfig(new URL('https://restaurant.example')).session).toBe('__Host-lgb_session');
  });
});
