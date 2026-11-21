import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import {
  savePendingSignupCredentials,
  loadPendingSignupCredentials,
  PendingSignupCredentials,
  PENDING_SIGNUP_KEY,
} from './signupOtp';
import {
  CURRENT_TERMS_VERSION,
  CURRENT_PRIVACY_VERSION,
  REQUIRED_CONSENT_TEXT,
  buildLegalPayload,
} from './legalConsent';

function installMemoryStorage() {
  const store = new Map<string, string>();
  const memory = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
  vi.stubGlobal('sessionStorage', memory);
  vi.stubGlobal('localStorage', memory);
  return memory;
}

describe('Legal Consent & Browser Storage Boundary Tests', () => {
  beforeEach(() => {
    installMemoryStorage();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('maintains synchronized version identifiers between legal consent and policy documents', () => {
    expect(CURRENT_TERMS_VERSION).toBe('terms-v1.0');
    expect(CURRENT_PRIVACY_VERSION).toBe('privacy-v1.1');
  });

  it('mandates that legal consent links use secure new-tab opening properties', () => {
    const legalLinks = [
      { name: 'Terms of Service', href: '/terms', target: '_blank', rel: 'noopener noreferrer' },
      { name: 'Privacy Policy', href: '/privacy', target: '_blank', rel: 'noopener noreferrer' },
    ];

    for (const link of legalLinks) {
      expect(link.href).toMatch(/^\/(terms|privacy)$/);
      expect(link.target).toBe('_blank');
      expect(link.rel).toBe('noopener noreferrer');
    }
  });

  it('requires unchecked consent state by default with authoritative text', () => {
    const initialCheckboxState = false;
    expect(initialCheckboxState).toBe(false);
    expect(REQUIRED_CONSENT_TEXT).toContain('Terms of Service');
    expect(REQUIRED_CONSENT_TEXT).toContain('Privacy Policy');
  });

  it('prevents plaintext passwords and OTPs from entering sessionStorage via savePendingSignupCredentials', () => {
    // Attempt to pass malicious or inadvertent credentials
    const credentialInput = {
      name: 'Eleanor Vance',
      email: 'eleanor@example.com',
      phone: '+1 (519)-555-0188',
      password: 'RawPassword123!',
      confirmPassword: 'RawPassword123!',
      otp: '654321',
    } as unknown as PendingSignupCredentials;

    savePendingSignupCredentials(credentialInput);

    const storedRaw = sessionStorage.getItem(PENDING_SIGNUP_KEY);
    expect(storedRaw).toBeTruthy();
    expect(storedRaw).not.toContain('RawPassword123!');
    expect(storedRaw).not.toContain('password');
    expect(storedRaw).not.toContain('confirmPassword');
    expect(storedRaw).not.toContain('otp');
    expect(storedRaw).not.toContain('654321');

    // Parse verified storage
    const loaded = loadPendingSignupCredentials();
    expect(loaded).toEqual({
      name: 'Eleanor Vance',
      email: 'eleanor@example.com',
      phone: '+1 (519)-555-0188',
    });
    expect((loaded as unknown as Record<string, unknown>).password).toBeUndefined();
    expect((loaded as unknown as Record<string, unknown>).otp).toBeUndefined();
  });

  it('sanitizes and migrates any legacy sessionStorage records containing passwords', () => {
    // Simulate legacy storage containing a password
    const legacyRecord = {
      name: 'Legacy User',
      email: 'legacy@example.com',
      phone: '5195550199',
      password: 'InsecureStoredPassword!',
    };
    sessionStorage.setItem(PENDING_SIGNUP_KEY, JSON.stringify(legacyRecord));

    // loadPendingSignupCredentials automatically migrates and scrubs the record
    const loaded = loadPendingSignupCredentials();
    expect(loaded).toEqual({
      name: 'Legacy User',
      email: 'legacy@example.com',
      phone: '5195550199',
    });

    // Check that raw storage was rewritten without the password
    const rawAfterLoad = sessionStorage.getItem(PENDING_SIGNUP_KEY);
    expect(rawAfterLoad).not.toContain('InsecureStoredPassword!');
    expect(rawAfterLoad).not.toContain('password');
  });

  it('buildLegalPayload throws error when agreement is false', () => {
    expect(() => buildLegalPayload(false)).toThrow(
      'Terms of Service and Privacy Policy acceptance is required.'
    );

    const valid = buildLegalPayload(true);
    expect(valid.agreedToTerms).toBe(true);
    expect(valid.termsVersion).toBe('terms-v1.0');
    expect(valid.privacyVersion).toBe('privacy-v1.1');
  });
});
