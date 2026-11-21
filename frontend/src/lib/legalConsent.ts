/**
 * Published legal policy version identifiers and client-side consent validation.
 * Synchronized with com.lordgainsborough.restaurant.service.LegalPolicyService.
 */

export const CURRENT_TERMS_VERSION = 'terms-v1.0';
export const CURRENT_PRIVACY_VERSION = 'privacy-v1.1';
export const REQUIRED_CONSENT_TEXT =
  'I agree to the Terms of Service and acknowledge that I have read the Privacy Policy.';

export interface LegalConsentState {
  agreedToTerms: boolean;
  termsVersion: string;
  privacyVersion: string;
}

export function buildLegalPayload(agreedToTerms: boolean): LegalConsentState {
  if (!agreedToTerms) {
    throw new Error('Terms of Service and Privacy Policy acceptance is required.');
  }
  return {
    agreedToTerms: true,
    termsVersion: CURRENT_TERMS_VERSION,
    privacyVersion: CURRENT_PRIVACY_VERSION,
  };
}
