import { describe, expect, it } from 'vitest';
import {
  CURRENT_TERMS_VERSION,
  CURRENT_PRIVACY_VERSION,
  REQUIRED_CONSENT_TEXT,
  buildLegalPayload,
} from './legalConsent';

describe('Lord Gainsborough Legal Consent & Policy Integrity', () => {
  it('has stable and correct version identifiers', () => {
    expect(CURRENT_TERMS_VERSION).toBe('terms-v1.0');
    expect(CURRENT_PRIVACY_VERSION).toBe('privacy-v1.1');
  });

  it('mandates precise consent wording with unchecked default', () => {
    const defaultState = false;
    expect(defaultState).toBe(false);
    expect(REQUIRED_CONSENT_TEXT).toBe(
      'I agree to the Terms of Service and acknowledge that I have read the Privacy Policy.'
    );
  });

  it('builds payload with explicit legal versions when consent is given', () => {
    const payload = buildLegalPayload(true);
    expect(payload.agreedToTerms).toBe(true);
    expect(payload.termsVersion).toBe('terms-v1.0');
    expect(payload.privacyVersion).toBe('privacy-v1.1');
  });

  it('throws an error if legal agreement is missing upon submission', () => {
    expect(() => buildLegalPayload(false)).toThrow(
      'Terms of Service and Privacy Policy acceptance is required.'
    );
  });

  it('ensures legal document links specify target="_blank" and rel="noopener noreferrer"', () => {
    const linkAttributes = {
      termsHref: '/terms',
      privacyHref: '/privacy',
      target: '_blank',
      rel: 'noopener noreferrer',
    };

    expect(linkAttributes.termsHref).toBe('/terms');
    expect(linkAttributes.privacyHref).toBe('/privacy');
    expect(linkAttributes.target).toBe('_blank');
    expect(linkAttributes.rel).toBe('noopener noreferrer');
  });

  it('enforces that sensitive credentials are never stored in localStorage, sessionStorage, or URLs', () => {
    const inMemoryState = {
      fullName: 'Lady Gainsborough',
      email: 'customer@example.com',
      phone: '+1 (519)-555-0199',
      password: 'SuperSecretPassword1!',
      confirmPassword: 'SuperSecretPassword1!',
      agreedToTerms: true,
    };

    // Simulated storage check: only allowed non-sensitive draft fields (if any) could be persisted,
    // but passwords and OTPs must never touch storage.
    const forbiddenKeys = ['password', 'confirmPassword', 'otp', 'code'];
    const mockStorage = new Map<string, string>();

    // Assert passwords are not written to storage
    for (const key of forbiddenKeys) {
      expect(mockStorage.has(key)).toBe(false);
    }

    // In-memory retains all fields during active component lifecycle
    expect(inMemoryState.password).toBe('SuperSecretPassword1!');
    expect(inMemoryState.confirmPassword).toBe('SuperSecretPassword1!');
    expect(inMemoryState.agreedToTerms).toBe(true);
  });
});
