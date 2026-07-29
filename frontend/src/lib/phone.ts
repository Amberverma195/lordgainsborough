function extractNationalDigits(raw: string): string {
  const allDigits = raw.replace(/\D/g, '');

  // Pasted full 11-digit North American number (e.g. 11234567890)
  if (allDigits.length >= 11 && allDigits.startsWith('1')) {
    return allDigits.slice(1, 11);
  }

  // Field already shows +1 prefix — first digit belongs to country code, not area code
  if (raw.trimStart().startsWith('+1')) {
    return allDigits.slice(1, 11);
  }

  // Plain entry before any formatting is applied
  return allDigits.slice(0, 10);
}

/** Public restaurant contact number (display + tel: href). */
export const RESTAURANT_PHONE_DISPLAY = '519-472-4168';
export const RESTAURANT_PHONE_TEL = '+15194724168';

export function formatPhoneInput(raw: string): string {
  const digits = extractNationalDigits(raw);

  if (digits.length === 0) return '';
  if (digits.length <= 3) return `+1 (${digits}`;
  if (digits.length <= 6) return `+1 (${digits.slice(0, 3)})-${digits.slice(3)}`;
  return `+1 (${digits.slice(0, 3)})-${digits.slice(3, 6)}-${digits.slice(6)}`;
}

export function getPhoneDigitCount(formatted: string): number {
  return extractNationalDigits(formatted).length;
}

/** Formats login input as a phone number when it looks numeric; otherwise leaves email text alone. */
export function formatLoginIdentifier(raw: string): string {
  if (/[a-zA-Z@]/.test(raw)) {
    return raw;
  }
  return formatPhoneInput(raw);
}

export function looksLikeEmail(identifier: string): boolean {
  return identifier.includes('@');
}
