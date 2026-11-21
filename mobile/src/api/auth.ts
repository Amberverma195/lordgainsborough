import { apiRequest } from './client';
import { ApiError } from './errors';
import {
  isRecord,
  parseLoginResponse,
  parseSessionUser,
  SessionUser,
} from './parse';
import {
  buildPasswordResetComplete,
  buildPasswordResetResend,
  buildPasswordResetSend,
  buildSignupOtpSend,
  buildSignupVerify,
  PasswordResetCompleteInput,
  SignupOtpSendInput,
  SignupVerifyInput,
} from './recovery';

export interface LoginInput {
  emailOrPhone: string;
  password: string;
}

export interface SignupOtpResult {
  code: string;
  message: string;
  challengeId: string | null;
  attemptToken: string | null;
  nextResendAt: string | null;
  resendsRemaining: number | null;
}

export interface PasswordResetResult {
  serverTime?: string | null;
  codeExpiresAt?: string | null;
  challengeExpiresAt?: string | null;
  resendsRemaining?: number | null;
  code: string;
  message: string;
  challengeId: string | null;
  nextResendAt: string | null;
  retryAfterSeconds: number | null;
}

function parseText(
  record: Record<string, unknown>,
  key: string,
): string | null {
  const val = record[key];
  return typeof val === 'string' && val.trim() ? val.trim() : null;
}

function parseNumber(
  record: Record<string, unknown>,
  key: string,
): number | null {
  const val = record[key];
  return typeof val === 'number' && Number.isFinite(val) ? val : null;
}

export function buildLoginBody(input: LoginInput): {
  emailOrPhone: string;
  password: string;
} {
  return {
    emailOrPhone: input.emailOrPhone.trim(),
    password: input.password,
  };
}

export async function login(input: LoginInput): Promise<SessionUser> {
  const response = await apiRequest('/api/auth/login', {
    method: 'POST',
    body: buildLoginBody(input),
  });
  const parsed = parseLoginResponse(response.body);
  if (!parsed.ok) {
    throw new ApiError({
      status: response.status,
      code: parsed.code,
      message: parsed.message,
      retryAfterSeconds: parsed.retryAfterSeconds,
    });
  }
  return parsed.user;
}

export async function fetchCurrentUser(token: string): Promise<SessionUser> {
  const response = await apiRequest('/api/auth/me', { token });
  const parsed = parseSessionUser(response.body);
  if (!parsed.ok) {
    throw new ApiError({
      status: response.status,
      code: 'MALFORMED_RESPONSE',
      message: parsed.message,
    });
  }
  return parsed.value;
}

export async function updateProfile(
  token: string,
  name: string,
): Promise<SessionUser> {
  const response = await apiRequest('/api/auth/profile', {
    method: 'PUT',
    token,
    body: { name: name.trim() },
  });
  const parsed = parseSessionUser(response.body);
  if (!parsed.ok) {
    throw new ApiError({
      status: response.status,
      code: 'MALFORMED_RESPONSE',
      message: parsed.message,
    });
  }
  return parsed.value;
}

export async function sendSignupOtp(
  input: SignupOtpSendInput,
): Promise<SignupOtpResult> {
  const built = buildSignupOtpSend(input);
  const response = await apiRequest(built.path, {
    method: 'POST',
    headers: built.headers,
    body: built.body,
  });
  const body = isRecord(response.body) ? response.body : {};
  return {
    code: parseText(body, 'code') ?? 'OTP_SENT',
    message: parseText(body, 'message') ?? 'Verification code sent.',
    challengeId: parseText(body, 'challengeId'),
    attemptToken: parseText(body, 'attemptToken'),
    nextResendAt: parseText(body, 'nextResendAt'),
    resendsRemaining: parseNumber(body, 'resendsRemaining'),
  };
}

export async function verifySignup(
  input: SignupVerifyInput,
): Promise<SessionUser> {
  const built = buildSignupVerify(input);
  const response = await apiRequest(built.path, {
    method: 'POST',
    headers: built.headers,
    body: built.body,
  });
  const parsed = parseLoginResponse(response.body);
  if (!parsed.ok) {
    throw new ApiError({
      status: response.status,
      code: parsed.code,
      message: parsed.message,
      retryAfterSeconds: parsed.retryAfterSeconds,
    });
  }
  return parsed.user;
}

export async function sendPasswordResetOtp(
  email: string,
  idempotencyKey?: string,
): Promise<PasswordResetResult> {
  const built = buildPasswordResetSend({ email, idempotencyKey });
  const response = await apiRequest(built.path, {
    method: 'POST',
    headers: built.headers,
    body: built.body,
  });
  const body = isRecord(response.body) ? response.body : {};
  return {
    code: parseText(body, 'code') ?? 'RESET_OTP_SENT',
    message:
      parseText(body, 'message') ??
      'If an account matches that email, a reset code was sent.',
    challengeId: parseText(body, 'challengeId'),
    nextResendAt: parseText(body, 'nextResendAt'),
    retryAfterSeconds: parseNumber(body, 'retryAfterSeconds'),
    serverTime: parseText(body, 'serverTime'),
    codeExpiresAt: parseText(body, 'codeExpiresAt'),
    challengeExpiresAt: parseText(body, 'challengeExpiresAt'),
    resendsRemaining: parseNumber(body, 'resendsRemaining'),
  };
}

export async function resendPasswordResetOtp(
  email: string,
  challengeId: string,
  idempotencyKey?: string,
): Promise<PasswordResetResult> {
  const built = buildPasswordResetResend({
    email,
    challengeId,
    idempotencyKey,
  });
  const response = await apiRequest(built.path, {
    method: 'POST',
    headers: built.headers,
    body: built.body,
  });
  const body = isRecord(response.body) ? response.body : {};
  return {
    code: parseText(body, 'code') ?? 'RESET_OTP_RESENT',
    message: parseText(body, 'message') ?? 'A new reset code has been sent.',
    challengeId: parseText(body, 'challengeId') ?? challengeId,
    nextResendAt: parseText(body, 'nextResendAt'),
    retryAfterSeconds: parseNumber(body, 'retryAfterSeconds'),
    serverTime: parseText(body, 'serverTime'),
    codeExpiresAt: parseText(body, 'codeExpiresAt'),
    challengeExpiresAt: parseText(body, 'challengeExpiresAt'),
    resendsRemaining: parseNumber(body, 'resendsRemaining'),
  };
}

export async function completePasswordReset(
  input: PasswordResetCompleteInput,
): Promise<void> {
  const built = buildPasswordResetComplete(input);
  await apiRequest(built.path, {
    method: 'POST',
    headers: built.headers,
    body: built.body,
  });
}

export async function deleteAccount(
  token: string,
  password: string,
): Promise<void> {
  await apiRequest('/api/auth/me', {
    method: 'DELETE',
    token,
    body: { password },
  });
}
