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
