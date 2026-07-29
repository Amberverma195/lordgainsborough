import { API_BASE } from '@/lib/orders';

export type SignupOtpCode =
  | 'OTP_SENT'
  | 'OTP_ALREADY_ACTIVE'
  | 'OTP_SEND_IN_PROGRESS'
  | 'OTP_DELIVERY_UNKNOWN'
  | 'OTP_RESEND_COOLDOWN'
  | 'OTP_RESEND_LIMIT_REACHED'
  | 'OTP_CHALLENGE_EXPIRED'
  | 'OTP_INVALID'
  | 'OTP_EXPIRED'
  | 'OTP_VERIFICATION_LOCKED'
  | 'OTP_VERIFIED';

export interface SignupOtpResponse {
  code?: SignupOtpCode;
  message?: string;
  challengeId?: string;
  serverTime?: string;
  nextResendAt?: string;
  otpExpiresAt?: string;
  challengeExpiresAt?: string;
  resendsRemaining?: number;
}

/** Non-secret challenge timing cached after send; never includes OTP. */
export interface SignupOtpChallengeState {
  challengeId?: string;
  email: string;
  serverTime?: string;
  nextResendAt?: string;
  otpExpiresAt?: string;
  challengeExpiresAt?: string;
  resendsRemaining?: number;
  code?: SignupOtpCode;
  message?: string;
}

/** Non-secret account details required to finish signup. */
export interface PendingSignupCredentials {
  name: string;
  email: string;
  phone: string;
}

export const PENDING_SIGNUP_KEY = 'pendingSignup';
export const SIGNUP_OTP_CHALLENGE_KEY = 'signupOtpChallenge';

export async function readSignupOtpResponse(res: Response): Promise<SignupOtpResponse> {
  try {
    return (await res.json()) as SignupOtpResponse;
  } catch {
    return {};
  }
}

/**
 * Backend response always wins. Builds authoritative local challenge state.
 */
export function applyBackendChallengeState(
  previous: SignupOtpChallengeState | null,
  body: SignupOtpResponse,
  emailFallback?: string
): SignupOtpChallengeState {
  const email = (body as { email?: string }).email
    || previous?.email
    || emailFallback
    || '';

  return {
    email,
    challengeId: body.challengeId ?? previous?.challengeId,
    serverTime: body.serverTime ?? previous?.serverTime,
    nextResendAt: body.nextResendAt ?? previous?.nextResendAt,
    otpExpiresAt: body.otpExpiresAt ?? previous?.otpExpiresAt,
    challengeExpiresAt: body.challengeExpiresAt ?? previous?.challengeExpiresAt,
    resendsRemaining: typeof body.resendsRemaining === 'number'
      ? body.resendsRemaining
      : previous?.resendsRemaining,
    code: body.code ?? previous?.code,
    message: body.message ?? previous?.message,
  };
}

/**
 * Countdown from authoritative server clocks only.
 * Returns 0 when nextResendAt is missing or already elapsed relative to serverTime.
 * Does not invent a fresh 30s window.
 */
export function cooldownSecondsFromServerClocks(
  serverTime?: string,
  nextResendAt?: string,
  nowMs: number = Date.now()
): number {
  if (!serverTime || !nextResendAt) {
    return 0;
  }
  const server = Date.parse(serverTime);
  const next = Date.parse(nextResendAt);
  if (!Number.isFinite(server) || !Number.isFinite(next)) {
    return 0;
  }
  // Align client clock to server using the skew observed at last sync.
  const skewMs = server - nowMs;
  const effectiveNow = nowMs + skewMs;
  return Math.max(0, Math.ceil((next - effectiveNow) / 1000));
}

export function cooldownSecondsFromResponse(
  res: Response,
  body: SignupOtpResponse,
  nowMs: number = Date.now()
): number {
  const retryAfter = Number(res.headers.get('Retry-After'));
  if (Number.isFinite(retryAfter) && retryAfter > 0) {
    return retryAfter;
  }
  return cooldownSecondsFromServerClocks(body.serverTime, body.nextResendAt, nowMs);
}

export function isChallengeExpiredState(
  state: SignupOtpChallengeState | null,
  nowMs: number = Date.now()
): boolean {
  if (!state) return false;
  if (state.code === 'OTP_CHALLENGE_EXPIRED') return true;
  if (!state.serverTime || !state.challengeExpiresAt) return false;
  const server = Date.parse(state.serverTime);
  const expires = Date.parse(state.challengeExpiresAt);
  if (!Number.isFinite(server) || !Number.isFinite(expires)) return false;
  const skewMs = server - nowMs;
  return nowMs + skewMs >= expires;
}

export function isResendExhausted(state: SignupOtpChallengeState | null): boolean {
  if (!state) return false;
  return state.code === 'OTP_RESEND_LIMIT_REACHED'
    || state.resendsRemaining === 0;
}

export function savePendingSignupCredentials(credentials: PendingSignupCredentials): void {
  if (typeof sessionStorage === 'undefined') return;
  const safe: PendingSignupCredentials = {
    name: credentials.name,
    email: credentials.email,
    phone: credentials.phone,
  };
  sessionStorage.setItem(PENDING_SIGNUP_KEY, JSON.stringify(safe));
}

export function loadPendingSignupCredentials(): PendingSignupCredentials | null {
  if (typeof sessionStorage === 'undefined') return null;
  const raw = sessionStorage.getItem(PENDING_SIGNUP_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as PendingSignupCredentials & { password?: unknown };
    if (!parsed?.email || !parsed?.name || !parsed?.phone) return null;
    const safe: PendingSignupCredentials = {
      name: parsed.name,
      email: parsed.email,
      phone: parsed.phone,
    };
    // Migrate any legacy value that contained a plaintext password.
    sessionStorage.setItem(PENDING_SIGNUP_KEY, JSON.stringify(safe));
    return safe;
  } catch {
    return null;
  }
}

export function clearPendingSignupCredentials(): void {
  if (typeof sessionStorage === 'undefined') return;
  sessionStorage.removeItem(PENDING_SIGNUP_KEY);
}

export function saveSignupOtpChallengeState(state: SignupOtpChallengeState): void {
  if (typeof sessionStorage === 'undefined') return;
  // Never persist OTP digits — state schema excludes them by type.
  sessionStorage.setItem(SIGNUP_OTP_CHALLENGE_KEY, JSON.stringify(state));
}

export function loadSignupOtpChallengeState(): SignupOtpChallengeState | null {
  if (typeof sessionStorage === 'undefined') return null;
  const raw = sessionStorage.getItem(SIGNUP_OTP_CHALLENGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as SignupOtpChallengeState & { otp?: unknown };
    if ('otp' in parsed) {
      delete parsed.otp;
    }
    if (!parsed?.email) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearSignupOtpChallengeState(): void {
  if (typeof sessionStorage === 'undefined') return;
  sessionStorage.removeItem(SIGNUP_OTP_CHALLENGE_KEY);
}

export function clearAllSignupSession(): void {
  clearPendingSignupCredentials();
  clearSignupOtpChallengeState();
}

export async function fetchSignupOtpStatus(
  email: string,
  challengeId?: string
): Promise<{ res: Response; body: SignupOtpResponse }> {
  const params = new URLSearchParams({ email });
  if (challengeId) {
    params.set('challengeId', challengeId);
  }
  const res = await fetch(`${API_BASE}/auth/signup/otp-status?${params.toString()}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
  });
  const body = await readSignupOtpResponse(res);
  return { res, body };
}

/**
 * One UUID per deliberate click; reuse only for transport retries of that click.
 */
export async function fetchWithIdempotentRetry(
  buildRequest: (idempotencyKey: string) => Promise<Response>,
  idempotencyKey: string = crypto.randomUUID()
): Promise<Response> {
  try {
    return await buildRequest(idempotencyKey);
  } catch {
    return buildRequest(idempotencyKey);
  }
}
