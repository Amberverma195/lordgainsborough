'use client';

import { useEffect, useState } from 'react';
import { notFound } from 'next/navigation';
import { persistAuth } from '@/lib/auth';
import { canAccessKds } from '@/lib/roles';

const AUTH_API = 'http://localhost:8082/api/auth';

export function useRequireKdsAccess() {
  const [isAuthorized, setIsAuthorized] = useState<boolean | null>(null);

  useEffect(() => {
    const verifyAccess = async () => {
      const token = localStorage.getItem('authToken');
      if (!token) {
        notFound();
        return;
      }

      try {
        const res = await fetch(`${AUTH_API}/me`, {
          headers: { Authorization: `Bearer ${token}` },
        });

        if (!res.ok) {
          notFound();
          return;
        }

        const data = await res.json();
        if (!canAccessKds(data.role)) {
          notFound();
          return;
        }

        persistAuth(data);
        setIsAuthorized(true);
      } catch {
        notFound();
      }
    };

    verifyAccess();
  }, []);

  return isAuthorized;
}
