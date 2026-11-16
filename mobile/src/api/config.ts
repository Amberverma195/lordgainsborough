export const DEFAULT_API_ORIGIN = 'http://localhost:8082';

export interface RuntimeHost {
  platform: string;
  isPhysicalDevice: boolean;
}

export function readConfiguredOrigin(
  env: Record<string, string | undefined>,
  key: string,
  fallback: string,
): string {
  const value = env[key];
  if (typeof value !== 'string' || value.trim() === '') {
    return fallback;
  }
  return value.trim().replace(/\/$/, '');
}

/**
 * Android emulators cannot reach the host machine through localhost.
 * Physical devices and the iOS simulator keep the configured host.
 */
export function adaptOriginForRuntime(origin: string, runtime: RuntimeHost): string {
  const trimmed = origin.replace(/\/$/, '');
  const loopback = /^https?:\/\/(localhost|127\.0\.0\.1)(?=[:/]|$)/i.test(trimmed);
  if (runtime.platform === 'android' && !runtime.isPhysicalDevice && loopback) {
    return trimmed.replace('://localhost', '://10.0.2.2').replace('://127.0.0.1', '://10.0.2.2');
  }
  return trimmed;
}

export function resolveMediaUrl(
  image: string | null | undefined,
  origins: { api: string; asset: string },
): string | null {
  if (typeof image !== 'string') {
    return null;
  }
  const value = image.trim();
  if (!value) {
    return null;
  }
  if (/^https?:\/\//i.test(value)) {
    return value;
  }
  const path = value.startsWith('/') ? value : `/${value}`;
  const origin = path.startsWith('/uploads') ? origins.api : origins.asset;
  return `${origin.replace(/\/$/, '')}${path}`;
}
