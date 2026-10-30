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

/**
 * Calculates Damerau-Levenshtein distance (insertions, deletions, substitutions, and adjacent transpositions).
 */
function damerauLevenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;

  const d: number[][] = [];
  for (let i = 0; i <= a.length; i++) {
    d[i] = [i];
  }
  for (let j = 0; j <= b.length; j++) {
    d[0][j] = j;
  }

  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(
        d[i - 1][j] + 1, // deletion
        d[i][j - 1] + 1, // insertion
        d[i - 1][j - 1] + cost // substitution
      );
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1); // transposition
      }
    }
  }
  return d[a.length][b.length];
}

const KNOWN_PROVIDER_DOMAINS = new Set([
  'gmail.com',
  'yahoo.com',
  'yahoo.ca',
  'outlook.com',
  'hotmail.com',
  'hotmail.ca',
  'icloud.com',
]);

const LEGITIMATE_EXEMPTIONS = new Set([
  'mail.com',
  'email.com',
  'ymail.com',
  'gmx.com',
  'gmx.net',
  'proton.me',
  'protonmail.com',
  'fastmail.com',
  'zoho.com',
  'aol.com',
  'live.com',
  'msn.com',
  'me.com',
  'mac.com',
]);

const FULL_DOMAIN_TYPOS: Record<string, string> = {
  'gmail.co': 'gmail.com',
  'gmail.cm': 'gmail.com',
  'gmail.con': 'gmail.com',
  'gmail.cmo': 'gmail.com',
  'gmail.comm': 'gmail.com',
  'gmail.coom': 'gmail.com',
  'gmail.om': 'gmail.com',
  'gmial.com': 'gmail.com',
  'gmai.com': 'gmail.com',
  'gamil.com': 'gmail.com',
  'gmaill.com': 'gmail.com',
  'gmal.com': 'gmail.com',
  'gimail.com': 'gmail.com',
  'gemail.com': 'gmail.com',
  'yaho.com': 'yahoo.com',
  'yahooo.com': 'yahoo.com',
  'yaho.co': 'yahoo.com',
  'yaho.ca': 'yahoo.ca',
  'yahoo.con': 'yahoo.com',
  'yahoo.cmo': 'yahoo.com',
  'yahoo.comm': 'yahoo.com',
  'outlok.com': 'outlook.com',
  'outloo.com': 'outlook.com',
  'outluk.com': 'outlook.com',
  'outlook.con': 'outlook.com',
  'outlook.cmo': 'outlook.com',
  'outlook.co': 'outlook.com',
  'hotmial.com': 'hotmail.com',
  'hotmai.com': 'hotmail.com',
  'hotmaill.com': 'hotmail.com',
  'hotmal.com': 'hotmail.com',
  'hotmail.con': 'hotmail.com',
  'hotmail.cmo': 'hotmail.com',
  'hotmail.co': 'hotmail.com',
  'icoud.com': 'icloud.com',
  'iclod.com': 'icloud.com',
  'iclou.com': 'icloud.com',
  'icloudd.com': 'icloud.com',
  'icloud.con': 'icloud.com',
  'icloud.co': 'icloud.com',
};

const INVALID_TLD_FIXES: Record<string, string> = {
  'con': 'com',
  'cmo': 'com',
  'comm': 'com',
  'coom': 'com',
  'cpm': 'com',
  'cca': 'ca',
  'ner': 'net',
  'nte': 'net',
  'ogr': 'org',
  'og': 'org',
  'orgg': 'org',
};

const PROVIDER_CONFIGS = [
  { targetSld: 'gmail', canonical: 'gmail.com', allowedTlds: ['com'] },
  { targetSld: 'yahoo', canonical: 'yahoo.com', allowedTlds: ['com', 'ca'] },
  { targetSld: 'outlook', canonical: 'outlook.com', allowedTlds: ['com'] },
  { targetSld: 'hotmail', canonical: 'hotmail.com', allowedTlds: ['com', 'ca'] },
  { targetSld: 'icloud', canonical: 'icloud.com', allowedTlds: ['com'] },
];

/**
 * Detects likely domain typos for popular providers and invalid TLDs.
 * Returns suggested email (e.g. 'person@gmail.com') or null if no typo detected.
 * Never converts legitimate domains like .co, .io, .org, or legitimate providers like mail.com.
 */
export function suggestEmailDomainTypo(email: string): string | null {
  if (!email || typeof email !== 'string') return null;

  const trimmed = email.trim();
  const atIndex = trimmed.lastIndexOf('@');
  if (atIndex <= 0 || atIndex !== trimmed.indexOf('@')) return null;

  const localPart = trimmed.slice(0, atIndex);
  const domainPart = trimmed.slice(atIndex + 1).toLowerCase();

  if (!domainPart || !localPart) return null;

  // Exact known providers and exemptions require no suggestion
  if (KNOWN_PROVIDER_DOMAINS.has(domainPart) || LEGITIMATE_EXEMPTIONS.has(domainPart)) {
    return null;
  }

  // 1. Direct explicit typo lookup
  if (FULL_DOMAIN_TYPOS[domainPart]) {
    return `${localPart}@${FULL_DOMAIN_TYPOS[domainPart]}`;
  }

  const dotIndex = domainPart.lastIndexOf('.');
  if (dotIndex <= 0) {
    return null;
  }

  const sld = domainPart.slice(0, dotIndex);
  const tld = domainPart.slice(dotIndex + 1);

  // 2. Provider SLD matching via edit distance
  for (const prov of PROVIDER_CONFIGS) {
    const sldDist = damerauLevenshtein(sld, prov.targetSld);
    if (sldDist <= 1) {
      if (prov.allowedTlds.includes(tld)) {
        if (sld !== prov.targetSld) {
          const suggestedDomain = prov.allowedTlds.length > 1 && tld === 'ca' ? `${prov.targetSld}.ca` : prov.canonical;
          return `${localPart}@${suggestedDomain}`;
        }
      } else {
        // TLD might be a typo of com/ca (e.g. con, cm, cmo, comm) or .co
        if (INVALID_TLD_FIXES[tld] === 'com' || tld === 'cm' || tld === 'co') {
          return `${localPart}@${prov.canonical}`;
        }
        if (INVALID_TLD_FIXES[tld] === 'ca') {
          return `${localPart}@${prov.targetSld}.ca`;
        }
      }
    }
  }

  // 3. Generic invalid TLD fixes for any domain (e.g. company.con -> company.com)
  // Legitimate TLDs like .co, .io, .org, .ca are NOT in INVALID_TLD_FIXES
  if (INVALID_TLD_FIXES[tld]) {
    const correctedTld = INVALID_TLD_FIXES[tld];
    return `${localPart}@${sld}.${correctedTld}`;
  }

  return null;
}
