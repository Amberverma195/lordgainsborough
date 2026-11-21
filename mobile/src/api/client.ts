import { fallbackMessage, toApiError } from './errors';
import { resolveApiOrigins } from './runtime';

export interface ApiResponse {
  status: number;
  body: unknown;
  retryAfterHeader: string | null;
}

export interface ApiRequestOptions {
  method?: string;
  body?: unknown;
  token?: string | null;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  notifyUnauthorized?: boolean;
}

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

export async function apiRequest(
  path: string,
  options: ApiRequestOptions = {},
): Promise<ApiResponse> {
  const origin = resolveApiOrigins().api;
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...options.headers,
  };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.token) {
    headers.Authorization = `Bearer ${options.token}`;
  }

  let response: Response;
  try {
    response = await fetch(`${origin}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body:
        options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  } catch {
    throw toApiError(0, { code: 'NETWORK_ERROR', message: fallbackMessage(0) });
  }

  const body = await readBody(response);
  const retryAfterHeader = response.headers.get('Retry-After');
  if (!response.ok) {
    if (
      options.token &&
      response.status === 401 &&
      options.notifyUnauthorized !== false
    ) {
      unauthorizedListeners.forEach((listener) => listener());
    }
    throw toApiError(response.status, body, retryAfterHeader);
  }
  return { status: response.status, body, retryAfterHeader };
}
