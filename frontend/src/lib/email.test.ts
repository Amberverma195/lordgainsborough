import { describe, expect, it } from 'vitest';
import { isValidEmail, normalizeEmail } from './email';

describe('isValidEmail', () => {
  it('rejects malformed email missing a domain suffix like amberverma@gma', () => {
    expect(isValidEmail('amberverma@gma')).toBe(false);
    expect(isValidEmail('test@localhost')).toBe(false);
    expect(isValidEmail('user@domain')).toBe(false);
  });

  it('rejects empty, whitespace, and missing parts', () => {
    expect(isValidEmail('')).toBe(false);
    expect(isValidEmail('   ')).toBe(false);
    expect(isValidEmail('@example.com')).toBe(false);
    expect(isValidEmail('user@')).toBe(false);
    expect(isValidEmail('user name@example.com')).toBe(false);
    expect(isValidEmail('user@ example.com')).toBe(false);
    expect(isValidEmail('user@example .com')).toBe(false);
  });

  it('rejects multiple @ symbols', () => {
    expect(isValidEmail('user@sub@example.com')).toBe(false);
  });

  it('rejects leading/trailing dots and consecutive dots', () => {
    expect(isValidEmail('.user@example.com')).toBe(false);
    expect(isValidEmail('user.@example.com')).toBe(false);
    expect(isValidEmail('user..name@example.com')).toBe(false);
    expect(isValidEmail('user@.example.com')).toBe(false);
    expect(isValidEmail('user@example..com')).toBe(false);
    expect(isValidEmail('user@example.com.')).toBe(false);
  });

  it('rejects single-letter TLD and numeric TLD', () => {
    expect(isValidEmail('user@example.c')).toBe(false);
    expect(isValidEmail('user@example.123')).toBe(false);
  });

  it('accepts valid modern email addresses, plus addressing, and subdomains', () => {
    expect(isValidEmail('amberverma@gmail.com')).toBe(true);
    expect(isValidEmail('amber+test@example.com')).toBe(true);
    expect(isValidEmail('user.name@sub.example.co.uk')).toBe(true);
    expect(isValidEmail('chef@lordgainsborough.restaurant')).toBe(true);
    expect(isValidEmail('guest_123-special@dept.hospitality.org')).toBe(true);
  });

  it('normalizes email properly', () => {
    expect(normalizeEmail('  AmberVerma@Gmail.COM  ')).toBe('amberverma@gmail.com');
  });
});
