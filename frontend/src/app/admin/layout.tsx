'use client';

import React from 'react';
import { useRequireAdmin } from '@/hooks/useRequireAdmin';

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const isAuthorized = useRequireAdmin();

  if (isAuthorized === null) {
    return (
      <div style={{
        minHeight: '60vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'var(--secondary)',
      }}>
        Verifying access...
      </div>
    );
  }

  return <>{children}</>;
}
