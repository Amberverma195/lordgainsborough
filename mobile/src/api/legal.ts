import { Platform } from 'react-native';

export const CURRENT_TERMS_VERSION = 'terms-v1.0';
export const CURRENT_PRIVACY_VERSION = 'privacy-v1.1';
export const PRODUCTION_VERCEL_LEGAL_ORIGIN =
  'https://lordgainsborough.vercel.app';
export const DEFAULT_LEGAL_ORIGIN =
  process.env.EXPO_PUBLIC_LEGAL_URL ||
  process.env.EXPO_PUBLIC_FRONTEND_URL ||
  PRODUCTION_VERCEL_LEGAL_ORIGIN;

export function getLegalDocumentUrl(
  path: '/terms' | '/privacy' | '/delete-account',
): string {
  const base =
    process.env.EXPO_PUBLIC_LEGAL_URL ||
    process.env.EXPO_PUBLIC_FRONTEND_URL ||
    DEFAULT_LEGAL_ORIGIN;
  const trimmed = base.replace(/\/$/, '');
  if (
    Platform.OS === 'android' &&
    /^https?:\/\/(localhost|127\.0\.0\.1)(?=[:/]|$)/i.test(trimmed)
  ) {
    return `${trimmed.replace('://localhost', '://10.0.2.2').replace('://127.0.0.1', '://10.0.2.2')}${path}`;
  }
  return `${trimmed}${path}`;
}
