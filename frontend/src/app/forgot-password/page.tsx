import { redirect } from 'next/navigation';

/**
 * Legacy /forgot-password route.
 * Redirects to /login?intent=recover so all authentication flows
 * operate under the unified /login state machine.
 */
export default function ForgotPasswordRedirect() {
  redirect('/login?intent=recover');
}
