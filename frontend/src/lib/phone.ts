export function extractNationalDigits(raw: string): string {
  if (!raw) return '';
  const allDigits = raw.replace(/\D/g, '');

  // Pasted full 11-digit or longer North American number starting with 1 (e.g. 11234567890)
  if (allDigits.length >= 11 && allDigits.startsWith('1')) {
    return allDigits.slice(1);
  }

  // Field already shows +1 prefix — first digit belongs to country code, not area code
  if (raw.trimStart().startsWith('+1')) {
    return allDigits.startsWith('1') ? allDigits.slice(1) : allDigits;
  }

  // Plain entry before any formatting is applied
  return allDigits;
}

/** Public restaurant contact number (display + tel: href). */
export const RESTAURANT_PHONE_DISPLAY = '519-472-4168';
export const RESTAURANT_PHONE_TEL = '+15194724168';

export function formatPhoneInput(raw: string): string {
  const digits = extractNationalDigits(raw);

  if (digits.length === 0) return '';
  if (digits.length <= 3) return `+1 (${digits}`;
  if (digits.length <= 6) return `+1 (${digits.slice(0, 3)})-${digits.slice(3)}`;
  if (digits.length <= 10) return `+1 (${digits.slice(0, 3)})-${digits.slice(3, 6)}-${digits.slice(6)}`;
  // Overlength: keep extra digits visible so the input is noticeably invalid and not silently truncated
  return `+1 (${digits.slice(0, 3)})-${digits.slice(3, 6)}-${digits.slice(6, 10)}-${digits.slice(10)}`;
}

export function getPhoneDigitCount(formatted: string): number {
  return extractNationalDigits(formatted).length;
}

export function isValidPhoneNumber(formatted: string): boolean {
  return extractNationalDigits(formatted).length === 10;
}

export function validatePhoneNumber(raw: string): { valid: boolean; message?: string } {
  const digits = extractNationalDigits(raw);
  if (digits.length === 0 || digits.length < 10) {
    return { valid: false, message: 'Please enter a valid 10-digit phone number.' };
  }
  if (digits.length > 10) {
    return { valid: false, message: 'Phone number cannot exceed 10 digits.' };
  }
  return { valid: true };
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
    // Pasted or autofilled input:
    // Support pasting formatted or raw numbers.
    // If overlength (>10 national digits), do NOT silently truncate.
    // Display a clear error and keep the formatted overlength input visible.
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

  // Normal typing or editing
  if (newDigits.length > 10) {
    // Attempted to type an 11th digit:
    // Stop accepting additional digits once 10 national digits are entered.
    return {
      formatted: previousFormatted,
      digits: prevDigits,
      error: prevDigits.length > 10 ? 'Phone number cannot exceed 10 digits.' : undefined,
      accepted: false,
    };
  }

  // Normal typing within 10 digits, or deleting/backspacing
  const formatted = formatPhoneInput(incomingRaw);
  return {
    formatted,
    digits: newDigits,
    error: undefined,
    accepted: true,
  };
}

/** Formats login input as a phone number when it looks numeric; otherwise leaves email text alone. */
export function formatLoginIdentifier(raw: string): string {
  if (/[a-zA-Z@]/.test(raw)) {
    return raw;
  }
  return formatPhoneInput(raw);
}

export function looksLikeEmail(identifier: string): boolean {
  return identifier != null && identifier.includes('@');
}

/** True when the input looks like an email or a valid North American phone number. */
export function isValidIdentifier(value: string): boolean {
  if (!value) return false;
  const trimmed = value.trim();
  if (looksLikeEmail(trimmed)) {
    // Defer to isValidEmail for strict checking — here we just ensure basic shape
    return trimmed.includes('@') && trimmed.indexOf('@') > 0 && trimmed.indexOf('@') < trimmed.length - 1;
  }
  return isValidPhoneNumber(trimmed);
}

/** Returns 'email', 'phone', or 'unknown' based on the input format. */
export function identifierType(value: string): 'email' | 'phone' | 'unknown' {
  if (!value) return 'unknown';
  const trimmed = value.trim();
  if (looksLikeEmail(trimmed)) return 'email';
  const digits = extractNationalDigits(trimmed);
  if (digits.length >= 1) return 'phone';
  return 'unknown';
}

/**
 * Normalize an identifier for backend submission.
 * - Emails: trimmed, lowercased
 * - Phones: E.164 format (+1XXXXXXXXXX)
 */
export function normalizeIdentifier(value: string): string {
  if (!value) return '';
  const trimmed = value.trim();
  if (looksLikeEmail(trimmed)) return trimmed.toLowerCase();
  const digits = extractNationalDigits(trimmed);
  if (digits.length === 10) return `+1${digits}`;
  return trimmed;
}

/** Sanitize identifier input: strip control chars, zero-width chars, limit length. */
export function sanitizeIdentifierInput(value: string): string {
  return value
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, '')
    .replace(/[\u200B-\u200D\uFEFF\u202A-\u202E]/g, '')
    .trimStart()
    .slice(0, 254);
}
