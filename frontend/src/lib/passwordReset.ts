import { readJsonObject } from './authSecurity';

export type PasswordResetCode =
  | 'RESET_CODE_SENT'
  | 'RESET_CODE_RESEND_COOLDOWN'
  | 'RESET_CODE_RESEND_LIMIT_REACHED'
  | 'RESET_CHALLENGE_EXPIRED'
  | 'RESET_RATE_LIMITED'
  | 'RESET_CODE_INVALID'
  | 'RESET_VERIFICATION_LOCKED'
  | 'PASSWORD_RESET_COMPLETED';

export interface PasswordResetResponse {
  code?: PasswordResetCode;
  message?: string;
  challengeId?: string;
  serverTime?: string;
  nextResendAt?: string;
  codeExpiresAt?: string;
  challengeExpiresAt?: string;
  resendsRemaining?: number;
  retryAfterSeconds?: number;
}

/** Safe reset metadata only. Never add an OTP or password to this interface. */
export interface PasswordResetChallengeState {
  email: string;
  challengeId: string;
  serverTime?: string;
  nextResendAt?: string;
  codeExpiresAt?: string;
  challengeExpiresAt?: string;
  resendsRemaining?: number;
  retryAfterSeconds?: number;
  /** Client time when serverTime was observed, used to preserve clock skew. */
  observedAt: number;
}

export const PASSWORD_RESET_CHALLENGE_KEY = 'passwordResetChallenge';

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function optionalNonNegativeNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
    ? value
    : undefined;
}

export async function readPasswordResetResponse(
  response: Response
): Promise<PasswordResetResponse> {
  return readJsonObject<PasswordResetResponse>(response) as Promise<PasswordResetResponse>;
}

export function toSafePasswordResetState(value: unknown): PasswordResetChallengeState | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const source = value as Record<string, unknown>;
  const email = optionalString(source.email)?.trim().toLowerCase();
  const challengeId = optionalString(source.challengeId);
  const observedAt = optionalNonNegativeNumber(source.observedAt);
  if (!email || !challengeId || observedAt === undefined) return null;

  return {
    email,
    challengeId,
    serverTime: optionalString(source.serverTime),
    nextResendAt: optionalString(source.nextResendAt),
    codeExpiresAt: optionalString(source.codeExpiresAt),
    challengeExpiresAt: optionalString(source.challengeExpiresAt),
    resendsRemaining: optionalNonNegativeNumber(source.resendsRemaining),
    retryAfterSeconds: optionalNonNegativeNumber(source.retryAfterSeconds),
    observedAt,
  };
}

export function applyPasswordResetResponse(
  previous: PasswordResetChallengeState | null,
  email: string,
  body: PasswordResetResponse,
  retryAfterSeconds: number,
  observedAt: number = Date.now()
): PasswordResetChallengeState | null {
  const challengeId = body.challengeId ?? previous?.challengeId;
  if (!challengeId) return null;

  const responseServerTime = optionalString(body.serverTime);
  const serverTime = responseServerTime
    ?? previous?.serverTime
    ?? new Date(observedAt).toISOString();
  const serverTimeMs = Date.parse(serverTime);
  const derivedNextResendAt =
    retryAfterSeconds > 0 && Number.isFinite(serverTimeMs)
      ? new Date(serverTimeMs + retryAfterSeconds * 1000).toISOString()
      : undefined;

  return {
    email: email.trim().toLowerCase(),
    challengeId,
    serverTime,
    nextResendAt: body.nextResendAt
      ?? derivedNextResendAt
      ?? previous?.nextResendAt,
    codeExpiresAt: body.codeExpiresAt ?? previous?.codeExpiresAt,
    challengeExpiresAt: body.challengeExpiresAt ?? previous?.challengeExpiresAt,
    resendsRemaining: optionalNonNegativeNumber(body.resendsRemaining)
      ?? previous?.resendsRemaining,
    retryAfterSeconds: retryAfterSeconds > 0
      ? retryAfterSeconds
      : optionalNonNegativeNumber(body.retryAfterSeconds),
    observedAt,
  };
}

function effectiveServerNow(
  state: PasswordResetChallengeState,
  nowMs: number
): number | null {
  const serverTime = state.serverTime ? Date.parse(state.serverTime) : Number.NaN;
  if (!Number.isFinite(serverTime) || !Number.isFinite(state.observedAt)) return null;
  return serverTime + Math.max(0, nowMs - state.observedAt);
}

export function passwordResetCooldownSeconds(
  state: PasswordResetChallengeState | null,
  nowMs: number = Date.now()
): number {
  if (!state) return 0;
  const serverNow = effectiveServerNow(state, nowMs);
  const nextResendAt = state.nextResendAt
    ? Date.parse(state.nextResendAt)
    : Number.NaN;
  if (serverNow !== null && Number.isFinite(nextResendAt)) {
    return Math.max(0, Math.ceil((nextResendAt - serverNow) / 1000));
  }
  if (state.retryAfterSeconds !== undefined) {
    const elapsedSeconds = Math.max(0, (nowMs - state.observedAt) / 1000);
    return Math.max(0, Math.ceil(state.retryAfterSeconds - elapsedSeconds));
  }
  return 0;
}

export function isPasswordResetChallengeExpired(
  state: PasswordResetChallengeState | null,
  nowMs: number = Date.now()
): boolean {
  if (!state?.challengeExpiresAt) return false;
  const expiresAt = Date.parse(state.challengeExpiresAt);
  const serverNow = effectiveServerNow(state, nowMs);
  return serverNow !== null && Number.isFinite(expiresAt) && serverNow >= expiresAt;
}

export function isPasswordResetResendExhausted(
  state: PasswordResetChallengeState | null
): boolean {
  return state?.resendsRemaining === 0;
}

export function savePasswordResetChallengeState(
  state: PasswordResetChallengeState
): void {
  if (typeof sessionStorage === 'undefined') return;
  const safe = toSafePasswordResetState(state);
  if (!safe) return;
  try {
    sessionStorage.setItem(PASSWORD_RESET_CHALLENGE_KEY, JSON.stringify(safe));
  } catch {
    // Session storage can be unavailable in private browsing modes.
  }
}

export function loadPasswordResetChallengeState(): PasswordResetChallengeState | null {
  if (typeof sessionStorage === 'undefined') return null;
  try {
    const raw = sessionStorage.getItem(PASSWORD_RESET_CHALLENGE_KEY);
    if (!raw) return null;
    return toSafePasswordResetState(JSON.parse(raw));
  } catch {
    return null;
  }
}

export function clearPasswordResetChallengeState(): void {
  if (typeof sessionStorage === 'undefined') return;
  try {
    sessionStorage.removeItem(PASSWORD_RESET_CHALLENGE_KEY);
  } catch {
    // Ignore unavailable storage.
  }
}

/** One UUID per deliberate action; a transport retry reuses that action's key. */
export async function fetchPasswordResetWithRetry(
  buildRequest: (idempotencyKey: string) => Promise<Response>,
  idempotencyKey: string
): Promise<Response> {
  try {
    return await buildRequest(idempotencyKey);
  } catch {
    return buildRequest(idempotencyKey);
  }
}
