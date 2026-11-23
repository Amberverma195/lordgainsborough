import { describe, expect, it } from 'vitest';
import {
  extractNationalDigits,
  formatPhoneInput,
  getPhoneDigitCount,
  identifierType,
  isValidIdentifier,
  isValidPhoneNumber,
  normalizeIdentifier,
  processPhoneInputChange,
  sanitizeIdentifierInput,
  validatePhoneNumber,
} from './phone';

describe('phone validation and formatting', () => {
  it('identifies 8 and 9 digit inputs as invalid', () => {
    expect(isValidPhoneNumber('12345678')).toBe(false);
    expect(getPhoneDigitCount('12345678')).toBe(8);

    expect(isValidPhoneNumber('123456789')).toBe(false);
    expect(getPhoneDigitCount('123456789')).toBe(9);
    expect(isValidPhoneNumber('+1 (123)-456-789')).toBe(false);
  });

  it('identifies 10 digit inputs as valid', () => {
    expect(isValidPhoneNumber('5195551234')).toBe(true);
    expect(getPhoneDigitCount('5195551234')).toBe(10);
    expect(isValidPhoneNumber('+1 (519)-555-1234')).toBe(true);
    expect(getPhoneDigitCount('+1 (519)-555-1234')).toBe(10);
  });

  it('handles 11-digit pasted numbers starting with 1 by stripping country code', () => {
    expect(isValidPhoneNumber('15195551234')).toBe(true);
    expect(getPhoneDigitCount('15195551234')).toBe(10);
    expect(extractNationalDigits('15195551234')).toBe('5195551234');
    expect(formatPhoneInput('15195551234')).toBe('+1 (519)-555-1234');
  });

  it('does not silently truncate overlength pasted numbers into a valid 10-digit number', () => {
    // 11 digits not starting with 1
    const over11 = '51955512345';
    expect(isValidPhoneNumber(over11)).toBe(false);
    expect(getPhoneDigitCount(over11)).toBe(11);
    expect(formatPhoneInput(over11)).toBe('+1 (519)-555-1234-5');

    // 12 digits
    const over12 = '519555123456';
    expect(isValidPhoneNumber(over12)).toBe(false);
    expect(getPhoneDigitCount(over12)).toBe(12);
    expect(formatPhoneInput(over12)).toBe('+1 (519)-555-1234-56');

    // 12 digits starting with 1 (e.g. 1 + 11 national digits)
    const over12WithCountry = '151955512345';
    expect(isValidPhoneNumber(over12WithCountry)).toBe(false);
    expect(getPhoneDigitCount(over12WithCountry)).toBe(11);
  });

  it('validates mixed email and phone identifiers', () => {
    expect(isValidPhoneNumber('5195551234')).toBe(true);
  });
});

describe('identifier helpers', () => {
  it('correctly classifies identifier types', () => {
    expect(identifierType('user@example.com')).toBe('email');
    expect(identifierType('5195551234')).toBe('phone');
    expect(identifierType('+1 (519)-555-1234')).toBe('phone');
    expect(identifierType('')).toBe('unknown');

    expect(isValidIdentifier('user@example.com')).toBe(true);
    expect(isValidIdentifier('5195551234')).toBe(true);
    expect(isValidIdentifier('+1 (519)-555-1234')).toBe(true);
    expect(isValidIdentifier('12345')).toBe(false);
    expect(isValidIdentifier('')).toBe(false);

    expect(normalizeIdentifier('User@Example.COM')).toBe('user@example.com');
    expect(normalizeIdentifier('5195551234')).toBe('+15195551234');
    expect(normalizeIdentifier('+1 (519)-555-1234')).toBe('+15195551234');

    expect(sanitizeIdentifierInput('  user\u200B@example.com  ')).toBe('user@example.com  ');
  });
});

describe('10-digit phone limit and interaction scenarios', () => {
  it('requirement 1: typing exactly 10 digits accumulates and formats properly', () => {
    let current = '';
    const digitsToType = '5195551234';

    for (const char of digitsToType) {
      const result = processPhoneInputChange(current, current + char, false);
      expect(result.accepted).toBe(true);
      expect(result.error).toBeUndefined();
      current = result.formatted;
    }

    expect(current).toBe('+1 (519)-555-1234');
    expect(validatePhoneNumber(current)).toEqual({ valid: true });
    expect(isValidPhoneNumber(current)).toBe(true);
  });

  it('requirement 2: attempting to type an 11th national digit is prevented', () => {
    const tenDigits = '+1 (519)-555-1234';
    // User tries to type '5' at the end of a 10-digit phone number
    const result = processPhoneInputChange(tenDigits, tenDigits + '5', false);
    expect(result.accepted).toBe(false);
    expect(result.formatted).toBe('+1 (519)-555-1234');
    expect(result.digits).toBe('5195551234');
    expect(result.error).toBeUndefined();
    expect(isValidPhoneNumber(result.formatted)).toBe(true);
  });

  it('requirement 3: pasting a valid 10-digit number formats correctly', () => {
    // Raw 10 digits
    const res1 = processPhoneInputChange('', '5195551234', true);
    expect(res1.accepted).toBe(true);
    expect(res1.formatted).toBe('+1 (519)-555-1234');
    expect(res1.error).toBeUndefined();
    expect(validatePhoneNumber(res1.formatted)).toEqual({ valid: true });

    // Pre-formatted
    const res2 = processPhoneInputChange('', '(519) 555-1234', true);
    expect(res2.accepted).toBe(true);
    expect(res2.formatted).toBe('+1 (519)-555-1234');
    expect(res2.error).toBeUndefined();
  });

  it('requirement 4: pasting a valid number with +1 or country code 1', () => {
    // With +1 prefix
    const res1 = processPhoneInputChange('', '+15195551234', true);
    expect(res1.accepted).toBe(true);
    expect(res1.formatted).toBe('+1 (519)-555-1234');
    expect(res1.error).toBeUndefined();
    expect(validatePhoneNumber(res1.formatted)).toEqual({ valid: true });

    // With 1 prefix
    const res2 = processPhoneInputChange('', '15195551234', true);
    expect(res2.accepted).toBe(true);
    expect(res2.formatted).toBe('+1 (519)-555-1234');
    expect(res2.error).toBeUndefined();
    expect(validatePhoneNumber(res2.formatted)).toEqual({ valid: true });

    // Formatted with +1
    const res3 = processPhoneInputChange('', '+1 (519) 555-1234', true);
    expect(res3.accepted).toBe(true);
    expect(res3.formatted).toBe('+1 (519)-555-1234');
    expect(res3.error).toBeUndefined();
  });

  it('requirement 5: pasting an overlength number rejects with clear error without silent truncation', () => {
    // 11 digits not starting with 1
    const res1 = processPhoneInputChange('', '51955512345', true);
    expect(res1.accepted).toBe(true);
    expect(res1.formatted).toBe('+1 (519)-555-1234-5'); // extra digit visible!
    expect(res1.error).toBe('Phone number cannot exceed 10 digits.');
    expect(validatePhoneNumber(res1.formatted)).toEqual({
      valid: false,
      message: 'Phone number cannot exceed 10 digits.',
    });

    // 12 digits starting with country code 1 (1 + 11 national digits)
    const res2 = processPhoneInputChange('', '151955512345', true);
    expect(res2.accepted).toBe(true);
    expect(res2.formatted).toBe('+1 (519)-555-1234-5');
    expect(res2.error).toBe('Phone number cannot exceed 10 digits.');
    expect(validatePhoneNumber(res2.formatted)).toEqual({
      valid: false,
      message: 'Phone number cannot exceed 10 digits.',
    });

    // Pasting with +1 prefix and overlength
    const res3 = processPhoneInputChange('', '+1 (519) 555-12345', true);
    expect(res3.accepted).toBe(true);
    expect(res3.formatted).toBe('+1 (519)-555-1234-5');
    expect(res3.error).toBe('Phone number cannot exceed 10 digits.');
  });

  it('requirement 6: backspacing and editing in the middle', () => {
    // Backspacing from 10 digits to 9 digits
    const res1 = processPhoneInputChange('+1 (519)-555-1234', '+1 (519)-555-123', false);
    expect(res1.accepted).toBe(true);
    expect(res1.formatted).toBe('+1 (519)-555-123');
    expect(res1.error).toBeUndefined();

    // Editing digits in the middle (replacing 555 with 444)
    const res2 = processPhoneInputChange('+1 (519)-555-1234', '+1 (519)-444-1234', false);
    expect(res2.accepted).toBe(true);
    expect(res2.formatted).toBe('+1 (519)-444-1234');
    expect(res2.error).toBeUndefined();

    // Deleting overlength digit clears the error
    const overlength = '+1 (519)-555-1234-5';
    const res3 = processPhoneInputChange(overlength, '+1 (519)-555-1234', false);
    expect(res3.accepted).toBe(true);
    expect(res3.formatted).toBe('+1 (519)-555-1234');
    expect(res3.error).toBeUndefined();
    expect(validatePhoneNumber(res3.formatted)).toEqual({ valid: true });
  });

  it('requirement 7: invalid short numbers return appropriate validation error', () => {
    expect(validatePhoneNumber('')).toEqual({
      valid: false,
      message: 'Please enter a valid 10-digit phone number.',
    });
    expect(validatePhoneNumber('519555123')).toEqual({
      valid: false,
      message: 'Please enter a valid 10-digit phone number.',
    });
    expect(validatePhoneNumber('+1 (519)-555-12')).toEqual({
      valid: false,
      message: 'Please enter a valid 10-digit phone number.',
    });
  });
});
