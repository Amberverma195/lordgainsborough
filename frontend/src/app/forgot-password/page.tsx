'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

/**
 * Legacy /forgot-password route.
 * Redirects to /login?intent=recover so all authentication flows
 * operate under the unified /login state machine.
 */
export default function ForgotPasswordRedirect() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/login?intent=recover');
  }, [router]);

  return null;
}
