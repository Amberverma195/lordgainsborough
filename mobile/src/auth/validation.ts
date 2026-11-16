export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_LENGTH = 128;
const SPECIAL_CHAR_REGEX = /[!@#$%^&*()_+\-=[\]{};':"\\|,.<>/?`~]/;

export function sanitizePassword(password: string): string {
  return password
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, '')
    .replace(/[\u200B-\u200D\uFEFF\u202A-\u202E]/g, '');
}

export function validatePassword(password: string): { valid: boolean; message?: string } {
  if (!password) {
    return { valid: false, message: 'Password is required.' };
  }
  const clean = sanitizePassword(password);
  if (clean.length < MIN_PASSWORD_LENGTH) {
    return { valid: false, message: 'Password must be at least 8 characters long.' };
  }
  if (clean.length > MAX_PASSWORD_LENGTH) {
    return { valid: false, message: 'Password must not exceed 128 characters.' };
  }
  if (!/[a-z]/.test(clean)) {
    return { valid: false, message: 'Password must contain at least one lowercase letter.' };
  }
  if (!/[A-Z]/.test(clean)) {
    return { valid: false, message: 'Password must contain at least one uppercase letter.' };
  }
  if (!/\d/.test(clean)) {
    return { valid: false, message: 'Password must contain at least one number.' };
  }
  if (!SPECIAL_CHAR_REGEX.test(clean)) {
    return { valid: false, message: 'Password must contain at least one special character (!@#$%...).' };
  }
  return { valid: true };
}

export function normalizePhone(raw: string): string {
  if (!raw) return '';
  let digits = raw.replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('1')) {
    digits = digits.slice(1);
  }
  return digits;
}

export function isValidPhone(phone: string): boolean {
  return normalizePhone(phone).length === 10;
}

export function isValidEmail(email: string): boolean {
  if (!email || email.length > 254) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
}
