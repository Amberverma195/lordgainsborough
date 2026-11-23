import {
  formatPhoneInput,
  isValidEmail,
  isValidPhone,
  normalizePhone,
  processPhoneInputChange,
  sanitizePassword,
  validatePassword,
  validatePhone,
} from '../validation';

describe('auth validation utilities', () => {
  describe('validatePassword', () => {
    it('accepts compliant passwords', () => {
      expect(validatePassword('Password123!')).toEqual({ valid: true });
      expect(validatePassword('Str0ng#Pass')).toEqual({ valid: true });
      expect(validatePassword('Abcd!123')).toEqual({ valid: true });
    });

    it('rejects empty passwords', () => {
      expect(validatePassword('')).toEqual({
        valid: false,
        message: 'Password is required.',
      });
    });

    it('rejects passwords shorter than 8 characters', () => {
      expect(validatePassword('Pass1!')).toEqual({
        valid: false,
        message: 'Password must be at least 8 characters long.',
      });
    });

    it('rejects passwords longer than 128 characters', () => {
      const longPass = 'A1!' + 'a'.repeat(126); // length 129
      expect(validatePassword(longPass)).toEqual({
        valid: false,
        message: 'Password must not exceed 128 characters.',
      });
    });

    it('rejects passwords missing lowercase letters', () => {
      expect(validatePassword('PASSWORD123!')).toEqual({
        valid: false,
        message: 'Password must contain at least one lowercase letter.',
      });
    });

    it('rejects passwords missing uppercase letters', () => {
      expect(validatePassword('password123!')).toEqual({
        valid: false,
        message: 'Password must contain at least one uppercase letter.',
      });
    });

    it('rejects passwords missing numbers', () => {
      expect(validatePassword('Password!!!!')).toEqual({
        valid: false,
        message: 'Password must contain at least one number.',
      });
    });

    it('rejects passwords missing special characters', () => {
      expect(validatePassword('Password1234')).toEqual({
        valid: false,
        message: 'Password must contain at least one special character (!@#$%...).',
      });
    });
  });

  describe('normalizePhone and isValidPhone', () => {
    it('normalizes 10-digit phone strings by stripping formatting', () => {
      expect(normalizePhone('(519) 555-0199')).toBe('5195550199');
      expect(normalizePhone('519.555.0199')).toBe('5195550199');
      expect(normalizePhone('519-555-0199')).toBe('5195550199');
    });

    it('strips leading 1 country code for 11-digit numbers', () => {
      expect(normalizePhone('+1 (519) 555-0199')).toBe('5195550199');
      expect(normalizePhone('15195550199')).toBe('5195550199');
    });

    it('validates 10-digit phone numbers', () => {
      expect(isValidPhone('5195550199')).toBe(true);
      expect(isValidPhone('(519) 555-0199')).toBe(true);
      expect(isValidPhone('12345')).toBe(false);
      expect(isValidPhone('0000000000')).toBe(true);
      expect(isValidPhone('')).toBe(false);
    });

    it('rejects overlength phone numbers and provides appropriate messages', () => {
      expect(validatePhone('5195551234')).toEqual({ valid: true });
      expect(validatePhone('+15195551234')).toEqual({ valid: true });
      expect(validatePhone('15195551234')).toEqual({ valid: true });

      expect(validatePhone('519555123')).toEqual({
        valid: false,
        message: 'Please enter a valid 10-digit phone number.',
      });
      expect(validatePhone('')).toEqual({
        valid: false,
        message: 'Please enter a valid 10-digit phone number.',
      });

      expect(validatePhone('51955512345')).toEqual({
        valid: false,
        message: 'Phone number cannot exceed 10 digits.',
      });
      expect(validatePhone('151955512345')).toEqual({
        valid: false,
        message: 'Phone number cannot exceed 10 digits.',
      });
    });

    it('handles typing and enforces 10-digit limit', () => {
      let current = '';
      const digitsToType = '5195551234';

      for (const char of digitsToType) {
        const result = processPhoneInputChange(current, current + char, false);
        expect(result.accepted).toBe(true);
        current = result.formatted;
      }

      expect(current).toBe('+1 (519)-555-1234');

      // Attempting to type an 11th digit is prevented
      const overflow = processPhoneInputChange(current, current + '5', false);
      expect(overflow.accepted).toBe(false);
      expect(overflow.formatted).toBe('+1 (519)-555-1234');
      expect(overflow.error).toBeUndefined();
    });

    it('handles pasting formatted numbers and rejects overlength without truncation', () => {
      // Valid paste
      const validPaste = processPhoneInputChange('', '+1 (519) 555-0199', true);
      expect(validPaste.accepted).toBe(true);
      expect(validPaste.formatted).toBe('+1 (519)-555-0199');
      expect(validPaste.error).toBeUndefined();

      // Overlength paste retains extra digits and flags error
      const overlengthPaste = processPhoneInputChange('', '51955512345', true);
      expect(overlengthPaste.accepted).toBe(true);
      expect(overlengthPaste.formatted).toBe('+1 (519)-555-1234-5');
      expect(overlengthPaste.error).toBe('Phone number cannot exceed 10 digits.');
    });

    it('handles editing and backspacing', () => {
      const result = processPhoneInputChange('+1 (519)-555-1234', '+1 (519)-555-123', false);
      expect(result.accepted).toBe(true);
      expect(result.formatted).toBe('+1 (519)-555-123');

      // Fixing overlength via backspace clears error
      const fixed = processPhoneInputChange('+1 (519)-555-1234-5', '+1 (519)-555-1234', false);
      expect(fixed.accepted).toBe(true);
      expect(fixed.formatted).toBe('+1 (519)-555-1234');
      expect(fixed.error).toBeUndefined();
    });
  });

  describe('isValidEmail', () => {
    it('accepts standard valid emails', () => {
      expect(isValidEmail('customer@example.com')).toBe(true);
      expect(isValidEmail('jane.doe+test@gainsborough.ca')).toBe(true);
    });

    it('rejects malformed email addresses', () => {
      expect(isValidEmail('')).toBe(false);
      expect(isValidEmail('customer@')).toBe(false);
      expect(isValidEmail('@example.com')).toBe(false);
      expect(isValidEmail('customer@example')).toBe(false);
      expect(isValidEmail('customer example.com')).toBe(false);
    });
  });

  describe('sanitizePassword', () => {
    it('removes null bytes and control characters from password input', () => {
      expect(sanitizePassword('pass\0word123!')).toBe('password123!');
      expect(sanitizePassword('securePass!1')).toBe('securePass!1');
    });
  });
});
