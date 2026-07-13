'use client';

import React from 'react';
import { usePathname } from 'next/navigation';
import Navbar from '@/components/Navbar';
import Footer from '@/components/Footer';
import CartSidebar from '@/components/CartSidebar';

const MINIMAL_LAYOUT_PATHS = ['/signup/verify', '/kds', '/admin'];

function matchesPath(pathname: string, paths: string[]) {
  return paths.some(path => pathname === path || pathname.startsWith(`${path}/`));
}

export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const minimalLayout = matchesPath(pathname, MINIMAL_LAYOUT_PATHS);

  if (minimalLayout) {
    return (
      <>
        <CartSidebar />
        <main style={{ minHeight: '100vh' }}>{children}</main>
      </>
    );
  }

  return (
    <>
      <Navbar />
      <CartSidebar />
      <main style={{ paddingTop: '80px', minHeight: 'calc(100vh - 80px)' }}>
        {children}
      </main>
      <Footer />
    </>
  );
}
