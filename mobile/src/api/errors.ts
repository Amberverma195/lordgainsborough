export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfterSeconds: number | null;

  constructor(options: {
    status: number;
    code: string;
    message: string;
    retryAfterSeconds?: number | null;
  }) {
    super(options.message);
    this.name = 'ApiError';
    this.status = options.status;
    this.code = options.code;
    this.retryAfterSeconds = options.retryAfterSeconds ?? null;
  }
}

export interface ParsedApiError {
  code: string;
  message: string;
  retryAfterSeconds: number | null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, unknown>;
}

function finiteNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
    return Number(value);
  }
  return null;
}

export function parseRetryAfterHeader(header: string | null): number | null {
  if (!header) {
    return null;
  }
  const seconds = Number(header.trim());
  if (!Number.isFinite(seconds) || seconds < 0) {
    return null;
  }
  return Math.ceil(seconds);
}

export function parseErrorPayload(
  status: number,
  body: unknown,
  retryAfterHeader: string | null = null,
): ParsedApiError {
  const headerRetry = parseRetryAfterHeader(retryAfterHeader);
  if (typeof body === 'string' && body.trim()) {
    return {
      code: status === 401 ? 'UNAUTHORIZED' : 'REQUEST_FAILED',
      message: body.trim(),
      retryAfterSeconds: headerRetry,
    };
  }

  const record = asRecord(body);
  const code = record && typeof record.code === 'string' && record.code.trim()
    ? record.code.trim()
    : status === 0
      ? 'NETWORK_ERROR'
      : 'REQUEST_FAILED';
  const message = record && typeof record.message === 'string' && record.message.trim()
    ? record.message.trim()
    : fallbackMessage(status);
  const bodyRetry = record ? finiteNumber(record.retryAfterSeconds) : null;
  return {
    code,
    message,
    retryAfterSeconds: bodyRetry ?? headerRetry,
  };
}

export function fallbackMessage(status: number): string {
  if (status === 0) {
    return 'We could not reach the restaurant. Check your connection and try again.';
  }
  if (status === 401) {
    return 'Your session is invalid or expired. Please sign in again.';
  }
  if (status === 403) {
    return 'You are not authorized to complete this request.';
  }
  if (status === 404) {
    return 'That information could not be found.';
  }
  if (status === 409) {
    return 'An order conflict occurred. Please review your cart and try again.';
  }
  if (status === 429) {
    return 'Too many requests. Please try again later.';
  }
  if (status === 503) {
    return 'Pickup orders are currently paused. Please check back later.';
  }
  return 'The restaurant could not complete this request. Please try again.';
}

export function toApiError(
  status: number,
  body: unknown,
  retryAfterHeader: string | null = null,
): ApiError {
  const parsed = parseErrorPayload(status, body, retryAfterHeader);
  return new ApiError({
    status,
    code: parsed.code,
    message: parsed.message,
    retryAfterSeconds: parsed.retryAfterSeconds,
  });
}
