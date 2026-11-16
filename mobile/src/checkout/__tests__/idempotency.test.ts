import {
  checkoutFingerprint,
  clearCheckoutIdempotencyKey,
  createSubmitGuard,
  getOrCreateCheckoutIdempotencyKey,
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

  it('blocks a second submission until the first one finishes', () => {
    const guard = createSubmitGuard();
    expect(guard.begin()).toBe(true);
    expect(guard.begin()).toBe(false);
    guard.end();
    expect(guard.begin()).toBe(true);
  });
});
