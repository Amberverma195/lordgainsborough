import type { AuthData } from './auth';

export type LoginResponseCode =
  | 'LOGIN_INVALID_CREDENTIALS'
  | 'LOGIN_RATE_LIMITED'
  | string;

export interface LoginApiResponse extends AuthData {
  code?: LoginResponseCode;
  message?: string;
  serverTime?: string;
  retryAfterSeconds?: number;
}

export interface ServerCooldownState {
  serverTime: string;
  nextAllowedAt: string;
  observedAt: number;
}

export async function readJsonObject<T extends object>(response: Response): Promise<Partial<T>> {
  try {
    const body: unknown = await response.json();
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      return {};
    }
    return body as Partial<T>;
  } catch {
    return {};
  }
}

export function parseRetryAfterSeconds(
  retryAfterHeader: string | null,
  serverTime?: string,
  nowMs: number = Date.now()
): number | null {
  if (!retryAfterHeader) return null;
  const value = retryAfterHeader.trim();
  if (!value) return null;

  const numericSeconds = Number(value);
  if (Number.isFinite(numericSeconds) && numericSeconds >= 0) {
    return Math.ceil(numericSeconds);
  }

  const retryAt = Date.parse(value);
  const parsedServerTime = serverTime ? Date.parse(serverTime) : Number.NaN;
  const baseline = Number.isFinite(parsedServerTime) ? parsedServerTime : nowMs;
  if (!Number.isFinite(retryAt)) return null;
  return Math.max(0, Math.ceil((retryAt - baseline) / 1000));
}

export function resolveRetryAfterSeconds(
  retryAfterHeader: string | null,
  bodyRetryAfterSeconds?: number,
  serverTime?: string,
  nowMs: number = Date.now()
): number {
  const headerSeconds = parseRetryAfterSeconds(retryAfterHeader, serverTime, nowMs);
  if (headerSeconds !== null) return headerSeconds;
  if (Number.isFinite(bodyRetryAfterSeconds) && (bodyRetryAfterSeconds ?? -1) >= 0) {
    return Math.ceil(bodyRetryAfterSeconds!);
  }
  return 0;
}

export function createServerCooldown(
  seconds: number,
  serverTime?: string,
  observedAt: number = Date.now()
): ServerCooldownState | null {
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  const parsedServerTime = serverTime ? Date.parse(serverTime) : Number.NaN;
  const serverNow = Number.isFinite(parsedServerTime) ? parsedServerTime : observedAt;
  return {
    serverTime: new Date(serverNow).toISOString(),
    nextAllowedAt: new Date(serverNow + Math.ceil(seconds) * 1000).toISOString(),
    observedAt,
  };
}

export function remainingServerCooldownSeconds(
  cooldown: ServerCooldownState | null,
  nowMs: number = Date.now()
): number {
  if (!cooldown) return 0;
  const serverTime = Date.parse(cooldown.serverTime);
  const nextAllowedAt = Date.parse(cooldown.nextAllowedAt);
  if (
    !Number.isFinite(serverTime)
    || !Number.isFinite(nextAllowedAt)
    || !Number.isFinite(cooldown.observedAt)
  ) {
    return 0;
  }
  const effectiveServerNow = serverTime + Math.max(0, nowMs - cooldown.observedAt);
  return Math.max(0, Math.ceil((nextAllowedAt - effectiveServerNow) / 1000));
}

export function loginErrorMessage(status: number, code?: string): string {
  if (status === 401 || code === 'LOGIN_INVALID_CREDENTIALS') {
    return 'The email, phone number, or password is incorrect.';
  }
  if (status === 429 || code === 'LOGIN_RATE_LIMITED') {
    return 'Too many sign-in attempts. Please wait before trying again.';
  }
  return 'Unable to sign in. Please try again.';
}
