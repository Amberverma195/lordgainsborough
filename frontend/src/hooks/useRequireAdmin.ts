'use client';

import { useEffect, useState } from 'react';
import { notFound } from 'next/navigation';
import { persistAuth } from '@/lib/auth';
import { isAdminRole } from '@/lib/roles';
import { API_BASE } from '@/lib/orders';

export function useRequireAdmin() {
  const [isAuthorized, setIsAuthorized] = useState<boolean | null>(null);

  useEffect(() => {
    const verifyAdmin = async () => {
      const token = localStorage.getItem('authToken');
      if (!token) {
        setIsAuthorized(false);
        return;
      }

      try {
        const res = await fetch(`${API_BASE}/auth/me`, {
          headers: { Authorization: `Bearer ${token}` },
        });

        if (!res.ok) {
          setIsAuthorized(false);
          return;
        }

        const data = await res.json();
        if (!isAdminRole(data.role)) {
          setIsAuthorized(false);
          return;
        }

        persistAuth(data);
        setIsAuthorized(true);
      } catch {
        setIsAuthorized(false);
      }
    };

    verifyAdmin();
  }, []);

  if (isAuthorized === false) notFound();
  return isAuthorized;
}
