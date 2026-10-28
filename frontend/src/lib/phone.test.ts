import { describe, expect, it } from 'vitest';
import { extractNationalDigits, formatPhoneInput, getPhoneDigitCount, isValidPhoneNumber } from './phone';

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
});
