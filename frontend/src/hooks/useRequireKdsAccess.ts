'use client';

import { useEffect, useState } from 'react';
import { notFound } from 'next/navigation';
import { API_BASE } from '@/lib/orders';

/**
 * Layer 1: page gate. Asks the backend whether this browser's IP is on the KDS allowlist.
 * Denied requests appear as a normal 404 (same as missing pages).
 */
export function useRequireKdsAccess() {
  const [isAuthorized, setIsAuthorized] = useState<boolean | null>(null);

  useEffect(() => {
    const verifyAccess = async () => {
      try {
        const res = await fetch(`${API_BASE}/kds/access`, {
          method: 'GET',
          cache: 'no-store',
        });

        if (!res.ok) {
          notFound();
          return;
        }

        setIsAuthorized(true);
      } catch {
        notFound();
      }
    };

    verifyAccess();
  }, []);

  return isAuthorized;
}
