/**
 * Unambiguous, collision-resistant account namespacing for client-side storage.
 *
 * Lossy string replacements (such as replacing `+` and `_` with `_`) can cause distinct
 * email addresses (e.g. `alice+promo@example.com` and `alice_promo@example.com`) to map
 * to the exact same storage key.
 *
 * This module encodes normalized email addresses into a deterministic, bijective UTF-8 hex
 * representation prefixed with `acc_`, ensuring zero collisions between distinct accounts.
 */

export function utf8ToHex(str: string): string {
  let hex = '';
  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i);
    // Handle UTF-16 surrogate pairs
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
      const next = str.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        code = (code - 0xd800) * 0x400 + (next - 0xdc00) + 0x10000;
        i++;
      }
    }
    if (code <= 0x7f) {
      hex += code.toString(16).padStart(2, '0');
    } else if (code <= 0x7ff) {
      hex += (0xc0 | (code >> 6)).toString(16).padStart(2, '0');
      hex += (0x80 | (code & 0x3f)).toString(16).padStart(2, '0');
    } else if (code <= 0xffff) {
      hex += (0xe0 | (code >> 12)).toString(16).padStart(2, '0');
      hex += (0x80 | ((code >> 6) & 0x3f)).toString(16).padStart(2, '0');
      hex += (0x80 | (code & 0x3f)).toString(16).padStart(2, '0');
    } else {
      hex += (0xf0 | (code >> 18)).toString(16).padStart(2, '0');
      hex += (0x80 | ((code >> 12) & 0x3f)).toString(16).padStart(2, '0');
      hex += (0x80 | ((code >> 6) & 0x3f)).toString(16).padStart(2, '0');
      hex += (0x80 | (code & 0x3f)).toString(16).padStart(2, '0');
    }
  }
  return hex;
}

export function hexToUtf8(hex: string): string | null {
  if (!hex || hex.length % 2 !== 0 || /[^0-9a-f]/i.test(hex)) {
    return null;
  }
  const bytes: number[] = [];
  for (let i = 0; i < hex.length; i += 2) {
    bytes.push(parseInt(hex.slice(i, i + 2), 16));
  }
  let str = '';
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i++];
    if (b0 <= 0x7f) {
      str += String.fromCharCode(b0);
    } else if (b0 >= 0xc2 && b0 <= 0xdf) {
      if (i >= bytes.length) return null;
      const b1 = bytes[i++];
      if ((b1 & 0xc0) !== 0x80) return null;
      str += String.fromCharCode(((b0 & 0x1f) << 6) | (b1 & 0x3f));
    } else if (b0 >= 0xe0 && b0 <= 0xef) {
      if (i + 1 >= bytes.length) return null;
      const b1 = bytes[i++];
      const b2 = bytes[i++];
      if ((b1 & 0xc0) !== 0x80 || (b2 & 0xc0) !== 0x80) return null;
      str += String.fromCharCode(((b0 & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f));
    } else if (b0 >= 0xf0 && b0 <= 0xf4) {
      if (i + 2 >= bytes.length) return null;
      const b1 = bytes[i++];
      const b2 = bytes[i++];
      const b3 = bytes[i++];
      if ((b1 & 0xc0) !== 0x80 || (b2 & 0xc0) !== 0x80 || (b3 & 0xc0) !== 0x80) return null;
      const cp = (((b0 & 0x07) << 18) | ((b1 & 0x3f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f)) - 0x10000;
      str += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    } else {
      return null;
    }
  }
  return str;
}

export const GUEST_NAMESPACE = 'guest';
export const ACCOUNT_NAMESPACE_PREFIX = 'acc_';

/**
 * Returns an unambiguous account namespace string for the given email address.
 * Returns 'guest' if the email is omitted or empty.
 */
export function toAccountNamespace(email?: string | null): string {
  if (!email || !email.trim()) {
    return GUEST_NAMESPACE;
  }
  const normalized = email.trim().toLowerCase();
  return `${ACCOUNT_NAMESPACE_PREFIX}${utf8ToHex(normalized)}`;
}

/**
 * Returns true if the namespace represents a guest (unauthenticated) context.
 */
export function isGuestNamespace(namespace: string): boolean {
  return namespace === GUEST_NAMESPACE;
}

/**
 * Recovers the normalized email from an account namespace string, or null if guest or invalid.
 */
export function fromAccountNamespace(namespace: string): string | null {
  if (!namespace || !namespace.startsWith(ACCOUNT_NAMESPACE_PREFIX)) {
    return null;
  }
  return hexToUtf8(namespace.slice(ACCOUNT_NAMESPACE_PREFIX.length));
}
