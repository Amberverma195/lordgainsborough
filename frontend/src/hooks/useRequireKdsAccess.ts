'use client';

import { apiFetch } from '@/lib/apiFetch';
import { useCallback, useEffect, useState } from 'react';
import { KDS_API_BASE } from '@/lib/orders';

export type KdsAccessStatus = 'loading' | 'authorized' | 'unauthenticated' | 'forbidden' | 'error';

export type KdsAccessState = {
  loading: boolean;
  authorized: boolean;
  status: KdsAccessStatus;
  retry: () => void;
};

/**
 * Validates whether current visitor can access the Kitchen Display System.
 * - Authenticated ADMIN: Allowed
 * - Authenticated STAFF: Allowed
 * - In-store IP allowlist: Allowed
 * - Unauthenticated: Returns 'unauthenticated' (prompts login)
 * - Ordinary customer: Returns 'forbidden' (access restricted)
 * - Connection failure: Returns 'error' with retry option
 */
export function useRequireKdsAccess(): KdsAccessState {
  const [state, setState] = useState<Omit<KdsAccessState, 'retry'>>({
    loading: true,
    authorized: false,
    status: 'loading',
  });
  const [retryCount, setRetryCount] = useState(0);

  const retry = useCallback(() => {
    setState({ loading: true, authorized: false, status: 'loading' });
    setRetryCount(count => count + 1);
  }, []);

  useEffect(() => {
    let active = true;

    const run = async () => {
      try {
        const res = await apiFetch(`${KDS_API_BASE}/kds/access`, {
          method: 'GET',
          cache: 'no-store',
        });

        if (!active) return;

        if (res.ok) {
          setState({ loading: false, authorized: true, status: 'authorized' });
          return;
        }

        try {
          const meRes = await apiFetch('/api/auth/me', { cache: 'no-store' });
          if (!active) return;
          if (meRes.ok) {
            setState({ loading: false, authorized: false, status: 'forbidden' });
          } else {
            setState({ loading: false, authorized: false, status: 'unauthenticated' });
          }
        } catch {
          if (active) {
            setState({ loading: false, authorized: false, status: 'unauthenticated' });
          }
        }
      } catch {
        if (active) {
          setState({ loading: false, authorized: false, status: 'error' });
        }
      }
    };

    void run();

    return () => {
      active = false;
    };
  }, [retryCount]);

  return {
    ...state,
    retry,
  };
}
