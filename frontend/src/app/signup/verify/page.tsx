'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

/**
 * Legacy /signup/verify route.
 * Redirects to /signup where the consolidated signup flow handles
 * both registration and OTP verification under a single route.
 */
export default function SignupVerifyRedirect() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/signup');
  }, [router]);

  return null;
}
