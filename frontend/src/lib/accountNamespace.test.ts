import { describe, it, expect } from 'vitest';
import {
  toAccountNamespace,
  fromAccountNamespace,
  isGuestNamespace,
  utf8ToHex,
  hexToUtf8,
  GUEST_NAMESPACE,
  ACCOUNT_NAMESPACE_PREFIX,
} from './accountNamespace';

describe('accountNamespace (Frontend)', () => {
  it('returns guest namespace for empty, null, or whitespace inputs', () => {
    expect(toAccountNamespace(undefined)).toBe(GUEST_NAMESPACE);
    expect(toAccountNamespace(null)).toBe(GUEST_NAMESPACE);
    expect(toAccountNamespace('')).toBe(GUEST_NAMESPACE);
    expect(toAccountNamespace('   ')).toBe(GUEST_NAMESPACE);
    expect(isGuestNamespace(GUEST_NAMESPACE)).toBe(true);
  });

  it('generates distinct namespaces for distinct emails with identical sanitization characters (zero collision)', () => {
    const ns1 = toAccountNamespace('alice+promo@example.com');
    const ns2 = toAccountNamespace('alice_promo@example.com');
    const ns3 = toAccountNamespace('alice.promo@example.com');

    expect(ns1).not.toBe(ns2);
    expect(ns1).not.toBe(ns3);
    expect(ns2).not.toBe(ns3);

    expect(fromAccountNamespace(ns1)).toBe('alice+promo@example.com');
    expect(fromAccountNamespace(ns2)).toBe('alice_promo@example.com');
    expect(fromAccountNamespace(ns3)).toBe('alice.promo@example.com');
  });

  it('normalizes case and surrounding whitespace deterministically', () => {
    const standard = toAccountNamespace('alice@example.com');
    const uppercase = toAccountNamespace('ALICE@EXAMPLE.COM');
    const untrimmed = toAccountNamespace('  alice@example.com  ');

    expect(uppercase).toBe(standard);
    expect(untrimmed).toBe(standard);
  });

  it('produces valid characters safe for storage keys', () => {
    const emails = [
      'user+test!#$%&\'*+-/=?^_`{|}~@example.com',
      'accented.é.ü.ñ@domain.com',
      'chinese.你好@domain.com',
    ];

    for (const email of emails) {
      const ns = toAccountNamespace(email);
      expect(ns.startsWith(ACCOUNT_NAMESPACE_PREFIX)).toBe(true);
      const hexPart = ns.slice(ACCOUNT_NAMESPACE_PREFIX.length);
      expect(/^[0-9a-f]+$/.test(hexPart)).toBe(true);

      const recovered = fromAccountNamespace(ns);
      expect(recovered).toBe(email.trim().toLowerCase());
    }
  });

  it('correctly handles utf8 roundtrip', () => {
    const sample = 'hello-world+test@example.com';
    const hex = utf8ToHex(sample);
    expect(hexToUtf8(hex)).toBe(sample);
  });
});
