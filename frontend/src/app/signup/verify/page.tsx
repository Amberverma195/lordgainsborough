import { redirect } from 'next/navigation';

/**
 * Legacy /signup/verify route.
 * Redirects to /signup where the consolidated signup flow handles
 * both registration and OTP verification under a single route.
 */
export default function SignupVerifyRedirect() {
  redirect('/signup');
}
