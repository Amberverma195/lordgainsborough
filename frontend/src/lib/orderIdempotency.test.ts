import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  getCartFingerprint,
  getOrCreateCheckoutIdempotencyKey,
  clearCheckoutIdempotencyKey,
  CHECKOUT_IDEMPOTENCY_KEY_STORAGE,
  CHECKOUT_CART_FINGERPRINT_STORAGE,
} from './orders';

function installMemorySessionStorage() {
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
  return memory;
}

describe('Order Idempotency Helpers', () => {
  beforeEach(() => {
    installMemorySessionStorage();
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('generates deterministic fingerprint regardless of item order', () => {
    const cartA = [
      { id: 'item-1', quantity: 2 },
      { id: 'item-2', quantity: 1 },
    ];
    const cartB = [
      { id: 'item-2', quantity: 1 },
      { id: 'item-1', quantity: 2 },
    ];

    expect(getCartFingerprint(cartA)).toBe(getCartFingerprint(cartB));
    expect(getCartFingerprint(cartA)).toBe('item-1:2|item-2:1');
  });

  it('generates and stores an idempotency key on first checkout attempt', () => {
    const cart = [{ id: 'dish-1', quantity: 1 }];
    const key = getOrCreateCheckoutIdempotencyKey(cart);

    expect(key).toBeTruthy();
    expect(sessionStorage.getItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE)).toBe(key);
    expect(sessionStorage.getItem(CHECKOUT_CART_FINGERPRINT_STORAGE)).toBe('dish-1:1');
  });

  it('reuses the exact same key on retry attempts for the same cart (lost connection / retry)', () => {
    const cart = [{ id: 'dish-1', quantity: 2 }];
    const firstAttemptKey = getOrCreateCheckoutIdempotencyKey(cart);

    // Simulate connection drop / user clicking Place Order again with same cart
    const retryAttemptKey = getOrCreateCheckoutIdempotencyKey(cart);

    expect(retryAttemptKey).toBe(firstAttemptKey);
  });

  it('generates a fresh key if the cart contents or quantities change', () => {
    const initialCart = [{ id: 'dish-1', quantity: 1 }];
    const firstKey = getOrCreateCheckoutIdempotencyKey(initialCart);

    // User modified cart
    const updatedCart = [{ id: 'dish-1', quantity: 2 }];
    const secondKey = getOrCreateCheckoutIdempotencyKey(updatedCart);

    expect(secondKey).not.toBe(firstKey);
    expect(sessionStorage.getItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE)).toBe(secondKey);
  });

  it('clears stored key and fingerprint when clearCheckoutIdempotencyKey is called', () => {
    const cart = [{ id: 'dish-1', quantity: 1 }];
    getOrCreateCheckoutIdempotencyKey(cart);

    expect(sessionStorage.getItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE)).toBeTruthy();

    clearCheckoutIdempotencyKey();

    expect(sessionStorage.getItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE)).toBeNull();
    expect(sessionStorage.getItem(CHECKOUT_CART_FINGERPRINT_STORAGE)).toBeNull();
  });
});
