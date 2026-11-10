import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { isAdminRole } from '@/lib/roles';
import { sessionCookieConfig } from '@/lib/sessionConfig';

export function proxy(request: NextRequest) {
  const pathname = request.nextUrl.pathname;
  const role = request.cookies.get('userRole')?.value;
  const cookies = sessionCookieConfig(request.nextUrl);
  const nonce = Buffer.from(crypto.randomUUID()).toString('base64');
  const backend = new URL(process.env.BACKEND_ORIGIN || process.env.NEXT_PUBLIC_BACKEND_ORIGIN || 'http://localhost:8082').origin;
  const socket = new URL(process.env.NEXT_PUBLIC_SOCKET_URL || 'http://localhost:9092');
  const wsOrigin = `${socket.protocol === 'https:' ? 'wss:' : 'ws:'}//${socket.host}`;
  const policy = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${process.env.NODE_ENV === 'development' ? " 'unsafe-eval'" : ''}`,
    "style-src 'self' 'unsafe-inline'", // Existing React/framer-motion styles use inline style attributes.
    `connect-src 'self' ${backend} ${socket.origin} ${wsOrigin}${process.env.NODE_ENV === 'development' ? ' ws://localhost:3000' : ''}`,
    `img-src 'self' data: blob: ${backend}`,
    "font-src 'self'", "object-src 'none'", "base-uri 'none'", "form-action 'self'", "frame-ancestors 'none'",
    ...(cookies.secure ? ['upgrade-insecure-requests'] : []),
  ].join('; ');
  const headers = new Headers(request.headers);
  headers.set('x-nonce', nonce);
  headers.set('Content-Security-Policy', policy);
  const decorate = (response: NextResponse) => {
    response.headers.set('Content-Security-Policy', policy);
    response.headers.set('X-Content-Type-Options', 'nosniff');
    response.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
    response.headers.set('Cache-Control', 'private, no-store');
    if (!request.cookies.get(cookies.csrf)) response.cookies.set(cookies.csrf,
      Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, '0')).join(''),
      { secure: cookies.secure, sameSite: 'lax', path: '/', maxAge: 28800 });
    return response;
  };

  if (pathname.startsWith('/admin') && (!request.cookies.get(cookies.session) || !isAdminRole(role))) {
    const url = request.nextUrl.clone();
    url.pathname = '/404';
    return decorate(NextResponse.rewrite(url, { request: { headers } }));
  }

  // KDS is IP-gated by the backend (page access-check + API + Socket.IO), not by login role.
  return decorate(NextResponse.next({ request: { headers } }));
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico|.*\\.(?:png|jpg|jpeg|webp|svg|ico|woff2?)$).*)'],
};
