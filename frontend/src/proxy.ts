import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { isAdminRole } from '@/lib/roles';

export function proxy(request: NextRequest) {
  const pathname = request.nextUrl.pathname;
  const role = request.cookies.get('userRole')?.value;

  if (pathname.startsWith('/admin') && !isAdminRole(role)) {
    const url = request.nextUrl.clone();
    url.pathname = '/404';
    return NextResponse.rewrite(url);
  }

  // KDS is IP-gated by the backend (page access-check + API + Socket.IO), not by login role.
  return NextResponse.next();
}

export const config = {
  matcher: ['/admin', '/admin/:path*', '/kds', '/kds/:path*'],
};
