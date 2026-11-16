import {
  isValidEmail,
  isValidPhone,
  normalizePhone,
  sanitizePassword,
  validatePassword,
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
