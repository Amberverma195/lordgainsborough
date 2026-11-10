import { NextRequest, NextResponse } from 'next/server';
import { sessionCookieConfig } from '@/lib/sessionConfig';
import { newCsrfToken, trustedClientIp, upstreamOrigin, validCsrf } from '@/lib/server/sessionSecurity';

export const runtime = 'nodejs';
const allowedRoots = new Set(['auth', 'orders', 'cart', 'categories', 'menu', 'reservations', 'store-settings', 'admin', 'kds']);
const forwardedHeaders = ['content-type', 'idempotency-key', 'x-signup-attempt-token', 'x-order-tracking-token'];

async function boundedBody(request: NextRequest, maxBytes: number): Promise<Uint8Array | undefined> {
  if (!request.body) return undefined;
  if (Number(request.headers.get('content-length')) > maxBytes) throw new Error('BODY_TOO_LARGE');
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > maxBytes) { await reader.cancel(); throw new Error('BODY_TOO_LARGE'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}

async function handle(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  if (!allowedRoots.has(path[0]) || path.some(part => !part || part === '.' || part === '..' || /[%/\\\u0000]/.test(part))) {
    return NextResponse.json({ message: 'Not found.' }, { status: 404 });
  }
  const route = path.join('/');
  const config = sessionCookieConfig(request.nextUrl);
  const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(request.method);
  if (unsafe && !validCsrf(request)) return NextResponse.json({ message: 'Request verification failed.' }, { status: 403 });
  const clearSession = (response: NextResponse) => {
    for (const name of [config.session, 'userRole']) response.cookies.set(name, '', {
      httpOnly: true, secure: config.secure, sameSite: 'lax', path: '/', maxAge: 0,
    });
  };
  if (route === 'auth/logout' && request.method === 'POST') {
    const response = new NextResponse(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
    clearSession(response);
    return response;
  }
  // Operational kitchen access must use the browser's real IP, not the frontend server's IP.
  if (path[0] === 'kds' && route !== 'kds/orders/all' && !route.startsWith('kds/reviews')) {
    return NextResponse.json({ message: 'Not found.' }, { status: 404 });
  }
  const headers = new Headers();
  for (const name of forwardedHeaders) { const value = request.headers.get(name); if (value) headers.set(name, value); }
  const session = request.cookies.get(config.session)?.value;
  if (session) headers.set('Authorization', `Bearer ${session}`);
  const ip = trustedClientIp(request);
  if (ip) { headers.set('X-Forwarded-For', ip); headers.set('X-Real-IP', ip); }
  try {
    const limit = route.endsWith('/messages') ? 16384 : route === 'menu/upload-image' ? 10 * 1024 * 1024 : 65536;
    const body = unsafe ? await boundedBody(request, limit) : undefined;
    const upstream = await fetch(`${upstreamOrigin()}/api/${path.map(encodeURIComponent).join('/')}${request.nextUrl.search}`, {
      method: request.method, headers, body: body as BodyInit | undefined, cache: 'no-store', redirect: 'manual',
      signal: AbortSignal.timeout(30000),
    });
    const responseHeaders = new Headers({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'" });
    responseHeaders.set('Content-Type', upstream.headers.get('content-type')?.includes('application/json')
      ? 'application/json' : 'text/plain; charset=utf-8');
    const retryAfter = upstream.headers.get('retry-after');
    if (retryAfter) responseHeaders.set('Retry-After', retryAfter);
    // Never relay redirects, backend cookies, or account JWTs to browser JavaScript.
    if (upstream.status >= 300 && upstream.status < 400) return NextResponse.json({ message: 'Invalid upstream response.' }, { status: 502 });
    const authResponse = ['auth/login', 'auth/signup', 'auth/me', 'auth/profile'].includes(route);
    let response: NextResponse;
    if (authResponse && upstream.headers.get('content-type')?.includes('application/json') && upstream.status !== 204) {
      const data = await upstream.json();
      const token = typeof data.token === 'string' ? data.token : undefined;
      delete data.token;
      response = NextResponse.json(data, { status: upstream.status, headers: responseHeaders });
      if (upstream.ok && token) {
        const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
        const maxAge = Math.max(0, Math.min(604800, Number(payload.exp) - Math.floor(Date.now() / 1000)));
        if (!Number.isFinite(maxAge) || maxAge <= 0) throw new Error('Invalid session expiration');
        response.cookies.set(config.session, token, { httpOnly: true, secure: config.secure, sameSite: 'lax', path: '/', maxAge });
        response.cookies.set('userRole', String(data.role || ''), { httpOnly: true, secure: config.secure, sameSite: 'lax', path: '/', maxAge });
        if (route === 'auth/login' || route === 'auth/signup') response.cookies.set(config.csrf, newCsrfToken(), {
          secure: config.secure, sameSite: 'lax', path: '/', maxAge: 28800,
        });
      }
    } else {
      response = new NextResponse(upstream.status === 204 || request.method === 'HEAD' ? null : upstream.body,
        { status: upstream.status, headers: responseHeaders });
    }
    if ((route === 'auth/me' && upstream.status === 401) || (route === 'auth/me' && request.method === 'DELETE' && upstream.ok)) clearSession(response);
    return response;
  } catch (error) {
    return NextResponse.json({ message: error instanceof Error && error.message === 'BODY_TOO_LARGE'
      ? 'Request body is too large.' : 'Unable to reach the server. Please try again.' },
    { status: error instanceof Error && error.message === 'BODY_TOO_LARGE' ? 413 : 502 });
  }
}

export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE, handle as HEAD };
