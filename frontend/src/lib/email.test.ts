import { describe, expect, it } from 'vitest';
import { isValidEmail, normalizeEmail, suggestEmailDomainTypo } from './email';

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
    expect(isValidEmail('founder@startup.io')).toBe(true);
    expect(isValidEmail('support@company.co')).toBe(true);
  });

  it('normalizes email properly', () => {
    expect(normalizeEmail('  AmberVerma@Gmail.COM  ')).toBe('amberverma@gmail.com');
  });
});

describe('suggestEmailDomainTypo', () => {
  it('suggests corrections for common major provider domain typos', () => {
    expect(suggestEmailDomainTypo('person@gmail.co')).toBe('person@gmail.com');
    expect(suggestEmailDomainTypo('person@gmial.com')).toBe('person@gmail.com');
    expect(suggestEmailDomainTypo('person@gmai.com')).toBe('person@gmail.com');
    expect(suggestEmailDomainTypo('person@gamil.com')).toBe('person@gmail.com');
    expect(suggestEmailDomainTypo('person@gmaill.com')).toBe('person@gmail.com');
    expect(suggestEmailDomainTypo('person@gmail.con')).toBe('person@gmail.com');
    expect(suggestEmailDomainTypo('person@yaho.com')).toBe('person@yahoo.com');
    expect(suggestEmailDomainTypo('person@yahooo.com')).toBe('person@yahoo.com');
    expect(suggestEmailDomainTypo('person@outlok.com')).toBe('person@outlook.com');
    expect(suggestEmailDomainTypo('person@outloo.com')).toBe('person@outlook.com');
    expect(suggestEmailDomainTypo('person@hotmial.com')).toBe('person@hotmail.com');
    expect(suggestEmailDomainTypo('person@hotmai.com')).toBe('person@hotmail.com');
    expect(suggestEmailDomainTypo('person@icoud.com')).toBe('person@icloud.com');
    expect(suggestEmailDomainTypo('person@iclod.com')).toBe('person@icloud.com');
  });

  it('suggests corrections for invalid TLD typos on custom domains', () => {
    expect(suggestEmailDomainTypo('person@mycompany.con')).toBe('person@mycompany.com');
    expect(suggestEmailDomainTypo('person@mycompany.cmo')).toBe('person@mycompany.com');
    expect(suggestEmailDomainTypo('person@mycompany.comm')).toBe('person@mycompany.com');
    expect(suggestEmailDomainTypo('person@mycompany.cca')).toBe('person@mycompany.ca');
    expect(suggestEmailDomainTypo('person@mycharity.ogr')).toBe('person@mycharity.org');
    expect(suggestEmailDomainTypo('person@myhost.ner')).toBe('person@myhost.net');
  });

  it('returns null for legitimate domains and providers without altering them', () => {
    // Exact popular providers
    expect(suggestEmailDomainTypo('person@gmail.com')).toBeNull();
    expect(suggestEmailDomainTypo('person@yahoo.com')).toBeNull();
    expect(suggestEmailDomainTypo('person@yahoo.ca')).toBeNull();
    expect(suggestEmailDomainTypo('person@outlook.com')).toBeNull();
    expect(suggestEmailDomainTypo('person@hotmail.com')).toBeNull();
    expect(suggestEmailDomainTypo('person@hotmail.ca')).toBeNull();
    expect(suggestEmailDomainTypo('person@icloud.com')).toBeNull();

    // Legitimate ccTLDs, modern gTLDs, and company domains
    expect(suggestEmailDomainTypo('person@company.co')).toBeNull();
    expect(suggestEmailDomainTypo('founder@startup.io')).toBeNull();
    expect(suggestEmailDomainTypo('info@charity.org')).toBeNull();
    expect(suggestEmailDomainTypo('service@agency.net')).toBeNull();
    expect(suggestEmailDomainTypo('user@canada.ca')).toBeNull();
    expect(suggestEmailDomainTypo('chef@lordgainsborough.restaurant')).toBeNull();

    // Legitimate alternative providers
    expect(suggestEmailDomainTypo('user@mail.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@ymail.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@proton.me')).toBeNull();
    expect(suggestEmailDomainTypo('user@protonmail.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@fastmail.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@zoho.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@aol.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@gmx.com')).toBeNull();
  });

  it('returns null for malformed or non-email inputs', () => {
    expect(suggestEmailDomainTypo('')).toBeNull();
    expect(suggestEmailDomainTypo('   ')).toBeNull();
    expect(suggestEmailDomainTypo('invalid')).toBeNull();
    expect(suggestEmailDomainTypo('user@')).toBeNull();
    expect(suggestEmailDomainTypo('@domain.com')).toBeNull();
    expect(suggestEmailDomainTypo('user@domain')).toBeNull();
  });
});

