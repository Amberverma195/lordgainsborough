const MAX_LENGTH = 254;
const MAX_LOCAL_LENGTH = 64;

const LOCAL_PART_PATTERN = /^(?![.])[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+(?<![.])$/;
const DOMAIN_LABEL_PATTERN = /^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$/;
const TLD_PATTERN = /^[a-zA-Z]{2,63}$/;

/**
 * Strict, RFC-compliant email validator shared across Lord Gainsborough auth flows.
 * Requires exactly one '@', valid local part, valid domain labels with at least one dot,
 * and a valid TLD (at least 2 letters). Rejects malformed addresses such as 'amberverma@gma'.
 */
export function isValidEmail(email: string): boolean {
  if (!email) {
    return false;
  }

  const trimmed = email.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_LENGTH) {
    return false;
  }

  if (/[\s]/.test(trimmed)) {
    return false;
  }

  const atIndex = trimmed.indexOf('@');
  if (atIndex <= 0 || atIndex !== trimmed.lastIndexOf('@')) {
    return false;
  }

  const localPart = trimmed.slice(0, atIndex);
  const domainPart = trimmed.slice(atIndex + 1);

  if (localPart.length === 0 || localPart.length > MAX_LOCAL_LENGTH) {
    return false;
  }

  if (localPart.startsWith('.') || localPart.endsWith('.') || localPart.includes('..')) {
    return false;
  }

  if (!LOCAL_PART_PATTERN.test(localPart)) {
    return false;
  }

  if (domainPart.length === 0 || domainPart.startsWith('.') || domainPart.endsWith('.') || domainPart.includes('..')) {
    return false;
  }

  // Domain must contain at least one dot (e.g. amberverma@gma is rejected)
  if (!domainPart.includes('.')) {
    return false;
  }

  const labels = domainPart.split('.');
  if (labels.length < 2) {
    return false;
  }

  for (let i = 0; i < labels.length - 1; i++) {
    const label = labels[i];
    if (!label || label.length > 63 || !DOMAIN_LABEL_PATTERN.test(label)) {
      return false;
    }
  }

  const tld = labels[labels.length - 1];
  if (!TLD_PATTERN.test(tld)) {
    return false;
  }

  return true;
}

export function normalizeEmail(email: string): string {
  if (!email) return '';
  return email
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, '')
    .replace(/[\u200B-\u200D\uFEFF\u202A-\u202E]/g, '')
    .trim()
    .toLowerCase();
}
