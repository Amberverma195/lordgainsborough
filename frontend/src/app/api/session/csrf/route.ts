import { NextRequest, NextResponse } from 'next/server';
import { sessionCookieConfig } from '@/lib/sessionConfig';
import { newCsrfToken } from '@/lib/server/sessionSecurity';

export function GET(request: NextRequest) {
  const origin = request.headers.get('origin');
  if ((origin && origin !== request.nextUrl.origin) || request.headers.get('sec-fetch-site') === 'cross-site') {
    return NextResponse.json({ message: 'Cross-origin request rejected.' }, { status: 403 });
  }
  const config = sessionCookieConfig(request.nextUrl);
  const existing = request.cookies.get(config.csrf)?.value;
  const token = existing && /^[a-f0-9]{64}$/.test(existing) ? existing : newCsrfToken();
  const response = NextResponse.json({ csrfToken: token }, { headers: { 'Cache-Control': 'no-store' } });
  response.cookies.set(config.csrf, token, { secure: config.secure, sameSite: 'lax', path: '/', maxAge: 28800 });
  return response;
}
