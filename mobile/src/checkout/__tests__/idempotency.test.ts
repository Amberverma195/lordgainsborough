import {
  checkoutFingerprint,
  clearAllCheckoutIdempotencyKeys,
  clearCheckoutIdempotencyKey,
  createSubmitGuard,
  getCheckoutFingerprintStorageKey,
  getCheckoutIdempotencyStorageKey,
  getOrCreateCheckoutIdempotencyKey,
  LEGACY_CHECKOUT_FINGERPRINT_KEY,
  LEGACY_CHECKOUT_IDEMPOTENCY_KEY,
  memoryStore,
} from '../idempotency';

const draft = {
  lines: [{ menuItemId: 'pie', quantity: 2 }, { menuItemId: 'soup', quantity: 1 }],
  pickupDate: '2026-11-16',
  pickupTime: 'asap',
  specialInstructions: 'No onions',
};

describe('order idempotency', () => {
  it('reuses the key for the same cart, pickup, and instructions', async () => {
    const store = memoryStore();
    const keys = ['key-a', 'key-b'];
    const random = () => keys.shift() ?? 'key-c';
    const first = await getOrCreateCheckoutIdempotencyKey(store, checkoutFingerprint(draft), random);
    const reversed = await getOrCreateCheckoutIdempotencyKey(store, checkoutFingerprint({
      ...draft,
      lines: [...draft.lines].reverse(),
    }), random);
    expect(reversed).toBe(first);
    expect(keys).toEqual(['key-b']);
  });

  it('creates a new key when the pickup time changes and can clear it', async () => {
    const store = memoryStore();
    const keys = ['key-a', 'key-b'];
    const random = () => keys.shift() ?? 'key-c';
    const first = await getOrCreateCheckoutIdempotencyKey(store, checkoutFingerprint(draft), random);
    const next = await getOrCreateCheckoutIdempotencyKey(store, checkoutFingerprint({
      ...draft,
      pickupTime: '6:00 PM',
    }), random);
    expect(next).not.toBe(first);
    await clearCheckoutIdempotencyKey(store);
    expect(store.snapshot()).toEqual({});
  });

  it('scopes idempotency keys to different accounts so users switching accounts do not conflict', async () => {
    const store = memoryStore();
    const keys = ['key-alice', 'key-bob'];
    const random = () => keys.shift() ?? 'key-extra';

    const fp = checkoutFingerprint(draft);
    const aliceKey = await getOrCreateCheckoutIdempotencyKey(store, fp, random, 'alice@example.com');
    expect(aliceKey).toBe('key-alice');

    // Bob orders with identical items on the same device
    const bobKey = await getOrCreateCheckoutIdempotencyKey(store, fp, random, 'bob@example.com');
    expect(bobKey).toBe('key-bob');
    expect(bobKey).not.toBe(aliceKey);

    // Alice still retrieves her own key
    const aliceRetried = await getOrCreateCheckoutIdempotencyKey(store, fp, random, 'alice@example.com');
    expect(aliceRetried).toBe('key-alice');

    // Clear only Alice's key
    await clearCheckoutIdempotencyKey(store, 'alice@example.com');
    const snap = store.snapshot();
    expect(snap[getCheckoutIdempotencyStorageKey('alice@example.com')]).toBeUndefined();
    expect(snap[getCheckoutIdempotencyStorageKey('bob@example.com')]).toBe('key-bob');
  });

  it('clears all account idempotency keys when clearAllCheckoutIdempotencyKeys is called', async () => {
    const store = memoryStore();
    const keys = ['key-1', 'key-2'];
    const random = () => keys.shift() ?? 'key-extra';

    const fp = checkoutFingerprint(draft);
    await getOrCreateCheckoutIdempotencyKey(store, fp, random, 'alice@example.com');
    await getOrCreateCheckoutIdempotencyKey(store, fp, random, 'bob@example.com');

    await clearAllCheckoutIdempotencyKeys(store);
    expect(store.snapshot()).toEqual({});
  });

  it('automatically purges legacy global keys', async () => {
    const store = memoryStore({
      [LEGACY_CHECKOUT_IDEMPOTENCY_KEY]: 'old-global-key',
      [LEGACY_CHECKOUT_FINGERPRINT_KEY]: 'old-fingerprint',
    });

    const key = await getOrCreateCheckoutIdempotencyKey(store, checkoutFingerprint(draft), () => 'new-user-key', 'user@example.com');
    expect(key).toBe('new-user-key');

    const snap = store.snapshot();
    expect(snap[LEGACY_CHECKOUT_IDEMPOTENCY_KEY]).toBeUndefined();
    expect(snap[LEGACY_CHECKOUT_FINGERPRINT_KEY]).toBeUndefined();
    expect(snap[getCheckoutIdempotencyStorageKey('user@example.com')]).toBe('new-user-key');
  });

  it('blocks a second submission until the first one finishes', () => {
    const guard = createSubmitGuard();
    expect(guard.begin()).toBe(true);
    expect(guard.begin()).toBe(false);
    guard.end();
    expect(guard.begin()).toBe(true);
  });
});
