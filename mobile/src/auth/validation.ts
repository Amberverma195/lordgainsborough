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

export function extractNationalDigits(raw: string): string {
  if (!raw) return '';
  const allDigits = raw.replace(/\D/g, '');

  if (allDigits.length >= 11 && allDigits.startsWith('1')) {
    return allDigits.slice(1);
  }

  if (raw.trimStart().startsWith('+1')) {
    return allDigits.startsWith('1') ? allDigits.slice(1) : allDigits;
  }

  return allDigits;
}

export function normalizePhone(raw: string): string {
  return extractNationalDigits(raw);
}

export function formatPhoneInput(raw: string): string {
  const digits = extractNationalDigits(raw);

  if (digits.length === 0) return '';
  if (digits.length <= 3) return `+1 (${digits}`;
  if (digits.length <= 6) return `+1 (${digits.slice(0, 3)})-${digits.slice(3)}`;
  if (digits.length <= 10) return `+1 (${digits.slice(0, 3)})-${digits.slice(3, 6)}-${digits.slice(6)}`;
  return `+1 (${digits.slice(0, 3)})-${digits.slice(3, 6)}-${digits.slice(6, 10)}-${digits.slice(10)}`;
}

export function validatePhone(phone: string): { valid: boolean; message?: string } {
  const digits = extractNationalDigits(phone);
  if (digits.length === 0 || digits.length < 10) {
    return { valid: false, message: 'Please enter a valid 10-digit phone number.' };
  }
  if (digits.length > 10) {
    return { valid: false, message: 'Phone number cannot exceed 10 digits.' };
  }
  return { valid: true };
}

export function isValidPhone(phone: string): boolean {
  return validatePhone(phone).valid;
}

export interface PhoneInputProcessResult {
  formatted: string;
  digits: string;
  error?: string;
  accepted: boolean;
}

export function processPhoneInputChange(
  previousFormatted: string,
  incomingRaw: string,
  isPaste = false
): PhoneInputProcessResult {
  const prevDigits = extractNationalDigits(previousFormatted);
  const newDigits = extractNationalDigits(incomingRaw);

  const isPastedOrAutofill = isPaste || Math.abs(newDigits.length - prevDigits.length) > 1;

  if (isPastedOrAutofill) {
    const formatted = formatPhoneInput(incomingRaw);
    if (newDigits.length > 10) {
      return {
        formatted,
        digits: newDigits,
        error: 'Phone number cannot exceed 10 digits.',
        accepted: true,
      };
    }
    return {
      formatted,
      digits: newDigits,
      error: undefined,
      accepted: true,
    };
  }

  if (newDigits.length > 10) {
    return {
      formatted: previousFormatted,
      digits: prevDigits,
      error: prevDigits.length > 10 ? 'Phone number cannot exceed 10 digits.' : undefined,
      accepted: false,
    };
  }

  const formatted = formatPhoneInput(incomingRaw);
  return {
    formatted,
    digits: newDigits,
    error: undefined,
    accepted: true,
  };
}

export function isValidEmail(email: string): boolean {
  if (!email || email.length > 254) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
}
