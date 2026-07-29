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
        notFound();
        return;
      }

      try {
        const res = await fetch(`${API_BASE}/auth/me`, {
          headers: { Authorization: `Bearer ${token}` },
        });

        if (!res.ok) {
          notFound();
          return;
        }

        const data = await res.json();
        if (!isAdminRole(data.role)) {
          notFound();
          return;
        }

        persistAuth(data);
        setIsAuthorized(true);
      } catch {
        notFound();
      }
    };

    verifyAdmin();
  }, []);

  return isAuthorized;
}
