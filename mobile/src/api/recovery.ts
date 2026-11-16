/**
 * Typed request builders for the existing signup OTP and password-reset APIs.
 * Screens do not call these yet. The next milestone can submit them without
 * inventing new endpoints or bypassing captcha, idempotency, or attempt tokens.
 */

export interface SignupOtpSendInput {
  email: string;
  phone: string;
  name: string;
  password: string;
  attemptToken?: string | null;
  restart?: boolean;
  idempotencyKey?: string | null;
  captchaToken?: string | null;
}

export interface SignupVerifyInput {
  email: string;
  otp: string;
  name: string;
  phone: string;
  password: string;
  challengeId?: string | null;
  attemptToken?: string | null;
}

export interface PasswordResetSendInput {
  email: string;
  idempotencyKey?: string | null;
}

export interface PasswordResetResendInput {
  email: string;
  challengeId: string;
  idempotencyKey?: string | null;
}

export interface PasswordResetCompleteInput {
  email: string;
  challengeId: string;
  otp: string;
  password: string;
  idempotencyKey?: string | null;
}

function optionalHeader(headers: Record<string, string>, name: string, value?: string | null) {
  if (value && value.trim()) {
    headers[name] = value.trim();
  }
}

export function buildSignupOtpSend(input: SignupOtpSendInput): {
  path: string;
  body: Record<string, unknown>;
  headers: Record<string, string>;
} {
  const headers: Record<string, string> = {};
  optionalHeader(headers, 'Idempotency-Key', input.idempotencyKey);
  optionalHeader(headers, 'X-Captcha-Token', input.captchaToken);
  optionalHeader(headers, 'X-Signup-Attempt-Token', input.attemptToken);
  return {
    path: '/api/auth/signup/send-otp',
    headers,
    body: {
      email: input.email.trim().toLowerCase(),
      phone: input.phone.trim(),
      name: input.name.trim(),
      password: input.password,
      ...(input.attemptToken ? { attemptToken: input.attemptToken } : {}),
      ...(input.restart ? { restart: true } : {}),
    },
  };
}

export function buildSignupVerify(input: SignupVerifyInput): {
  path: string;
  body: Record<string, unknown>;
  headers: Record<string, string>;
} {
  const headers: Record<string, string> = {};
  optionalHeader(headers, 'X-Signup-Attempt-Token', input.attemptToken);
  return {
    path: '/api/auth/signup',
    headers,
    body: {
      email: input.email.trim().toLowerCase(),
      otp: input.otp.trim(),
      name: input.name.trim(),
      phone: input.phone.trim(),
      password: input.password,
      ...(input.challengeId ? { challengeId: input.challengeId } : {}),
      ...(input.attemptToken ? { attemptToken: input.attemptToken } : {}),
    },
  };
}

export function buildPasswordResetSend(input: PasswordResetSendInput): {
  path: string;
  body: { email: string };
  headers: Record<string, string>;
} {
  const headers: Record<string, string> = {};
  optionalHeader(headers, 'Idempotency-Key', input.idempotencyKey);
  return {
    path: '/api/auth/forgot-password/send-otp',
    headers,
    body: { email: input.email.trim().toLowerCase() },
  };
}

export function buildPasswordResetResend(input: PasswordResetResendInput): {
  path: string;
  body: { email: string; challengeId: string };
  headers: Record<string, string>;
} {
  const headers: Record<string, string> = {};
  optionalHeader(headers, 'Idempotency-Key', input.idempotencyKey);
  return {
    path: '/api/auth/forgot-password/resend-otp',
    headers,
    body: {
      email: input.email.trim().toLowerCase(),
      challengeId: input.challengeId,
    },
  };
}

export function buildPasswordResetComplete(input: PasswordResetCompleteInput): {
  path: string;
  body: { email: string; challengeId: string; otp: string; password: string };
  headers: Record<string, string>;
} {
  const headers: Record<string, string> = {};
  optionalHeader(headers, 'Idempotency-Key', input.idempotencyKey);
  return {
    path: '/api/auth/forgot-password/reset',
    headers,
    body: {
      email: input.email.trim().toLowerCase(),
      challengeId: input.challengeId,
      otp: input.otp.trim(),
      password: input.password,
    },
  };
}
