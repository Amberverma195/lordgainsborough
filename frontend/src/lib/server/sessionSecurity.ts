import { timingSafeEqual, randomBytes } from 'node:crypto';
import type { NextRequest } from 'next/server';
import { sessionCookieConfig } from '../sessionConfig';

export const newCsrfToken = () => randomBytes(32).toString('hex');

export function validCsrf(request: NextRequest): boolean {
  if (request.headers.get('origin') !== request.nextUrl.origin) return false;
  if (request.headers.get('sec-fetch-site') === 'cross-site') return false;
  const expected = request.cookies.get(sessionCookieConfig(request.nextUrl).csrf)?.value || '';
  const supplied = request.headers.get('x-csrf-token') || '';
  return /^[a-f0-9]{64}$/.test(expected) && /^[a-f0-9]{64}$/.test(supplied)
    && timingSafeEqual(Buffer.from(expected), Buffer.from(supplied));
}

export function upstreamOrigin(): string {
  const url = new URL(process.env.BACKEND_ORIGIN || process.env.NEXT_PUBLIC_BACKEND_ORIGIN || 'http://localhost:8082');
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('Invalid backend origin');
  return url.origin;
}

/** Only trust headers sanitized by the deployed ingress, never arbitrary forwarding headers. */
export function trustedClientIp(request: NextRequest): string | undefined {
  const header = process.env.TRUSTED_CLIENT_IP_HEADER || (process.env.VERCEL === '1' ? 'x-vercel-forwarded-for' : '');
  return header ? request.headers.get(header)?.split(',')[0]?.trim() : undefined;
}
