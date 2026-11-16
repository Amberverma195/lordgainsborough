import {
  fromAccountNamespace,
  GUEST_NAMESPACE,
  hexToUtf8,
  isGuestNamespace,
  toAccountNamespace,
  utf8ToHex,
} from '../accountNamespace';

describe('accountNamespace', () => {
  it('maps undefined, null, empty, or whitespace email to guest namespace', () => {
    expect(toAccountNamespace(undefined)).toBe(GUEST_NAMESPACE);
    expect(toAccountNamespace(null)).toBe(GUEST_NAMESPACE);
    expect(toAccountNamespace('')).toBe(GUEST_NAMESPACE);
    expect(toAccountNamespace('   ')).toBe(GUEST_NAMESPACE);
    expect(isGuestNamespace(GUEST_NAMESPACE)).toBe(true);
    expect(fromAccountNamespace(GUEST_NAMESPACE)).toBeNull();
  });

  it('guarantees collision resistance between alice+promo@example.com and alice_promo@example.com', () => {
    const ns1 = toAccountNamespace('alice+promo@example.com');
    const ns2 = toAccountNamespace('alice_promo@example.com');
    expect(ns1).not.toBe(ns2);

    expect(fromAccountNamespace(ns1)).toBe('alice+promo@example.com');
    expect(fromAccountNamespace(ns2)).toBe('alice_promo@example.com');
  });

  it('normalizes case and surrounding whitespace consistently', () => {
    const standard = toAccountNamespace('alice@example.com');
    const uppercase = toAccountNamespace('ALICE@EXAMPLE.COM');
    const untrimmed = toAccountNamespace('  alice@example.com  ');

    expect(uppercase).toBe(standard);
    expect(untrimmed).toBe(standard);
    expect(fromAccountNamespace(standard)).toBe('alice@example.com');
  });

  it('handles special characters commonly found in email addresses', () => {
    const specialEmails = [
      'user+tag@domain.com',
      'user-dash@domain.com',
      'user.dot@domain.com',
      'user_underscore@domain.com',
      'user=equals@domain.com',
      'user/slash@domain.com',
      'user!excl@domain.com',
      'user#hash@domain.com',
      'user$dollar@domain.com',
      'user%percent@domain.com',
      'user&amp@domain.com',
      'user*star@domain.com',
    ];

    const namespaces = new Set<string>();
    for (const email of specialEmails) {
      const ns = toAccountNamespace(email);
      expect(namespaces.has(ns)).toBe(false);
      namespaces.add(ns);
      expect(fromAccountNamespace(ns)).toBe(email);
      // Ensure only characters allowed in SecureStore keys [a-zA-Z0-9._-]
      expect(ns).toMatch(/^[a-z0-9_]+$/);
    }
  });

  it('supports unicode and internationalized characters with bidirectional fidelity', () => {
    const internationalEmails = [
      'josé@restaurant.ca',
      'münchen@domain.de',
      'инфо@домен.рф',
      'user🍕@pizza.com',
    ];

    for (const email of internationalEmails) {
      const ns = toAccountNamespace(email);
      expect(ns).toMatch(/^[a-z0-9_]+$/);
      expect(fromAccountNamespace(ns)).toBe(email);
    }
  });

  it('returns null when attempting to decode invalid or non-hex namespace', () => {
    expect(fromAccountNamespace('')).toBeNull();
    expect(fromAccountNamespace('guest')).toBeNull();
    expect(fromAccountNamespace('invalid_prefix_123')).toBeNull();
    expect(fromAccountNamespace('acc_invalidhex!')).toBeNull();
    expect(fromAccountNamespace('acc_1')).toBeNull(); // Odd length
    expect(hexToUtf8('123')).toBeNull(); // Odd length
    expect(hexToUtf8('zz')).toBeNull(); // Non-hex
  });

  it('utf8ToHex produces identical output to Buffer hex encoding', () => {
    const testCases = [
      'test@example.com',
      'alice+promo@example.com',
      'café-au-lait@domain.fr',
      'emoji🚀test@domain.com',
    ];

    for (const testStr of testCases) {
      const customHex = utf8ToHex(testStr);
      const nodeBufferHex = Buffer.from(testStr, 'utf8').toString('hex');
      expect(customHex).toBe(nodeBufferHex);
    }
  });
});
