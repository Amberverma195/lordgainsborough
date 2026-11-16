import { apiRequest } from './client';
import { ApiError } from './errors';
import { SessionUser, parseLoginResponse, parseSessionUser } from './parse';

export interface LoginInput {
  emailOrPhone: string;
  password: string;
}

export function buildLoginBody(input: LoginInput): { emailOrPhone: string; password: string } {
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
    throw new ApiError({ status: response.status, code: 'MALFORMED_RESPONSE', message: parsed.message });
  }
  return parsed.value;
}
