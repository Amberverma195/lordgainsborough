import {
  GUEST_CART_KEY,
  GUEST_PICKUP_KEY,
  LEGACY_CART_KEY,
  LEGACY_PICKUP_KEY,
  getCartStorageKey,
  getPickupStorageKey,
} from '../storage';

describe('cart storage keys', () => {
  it('returns guest keys when no user is provided', () => {
    expect(getCartStorageKey()).toBe(GUEST_CART_KEY);
    expect(getCartStorageKey(null)).toBe(GUEST_CART_KEY);
    expect(getCartStorageKey('')).toBe(GUEST_CART_KEY);
    expect(getCartStorageKey('   ')).toBe(GUEST_CART_KEY);

    expect(getPickupStorageKey()).toBe(GUEST_PICKUP_KEY);
    expect(getPickupStorageKey(null)).toBe(GUEST_PICKUP_KEY);
    expect(getPickupStorageKey('')).toBe(GUEST_PICKUP_KEY);
  });

  it('partitions keys per customer email, normalized to lowercase', () => {
    const keyA = getCartStorageKey('Customer.A@example.com');
    const keyB = getCartStorageKey('customer.b@example.com');
    const keyALower = getCartStorageKey('customer.a@example.com');

    expect(keyA).toBe('lgb.cart.u.customer.a_example.com.v1');
    expect(keyB).toBe('lgb.cart.u.customer.b_example.com.v1');
    expect(keyA).toBe(keyALower);
    expect(keyA).not.toBe(keyB);
    expect(keyA).not.toBe(LEGACY_CART_KEY);

    const pickupA = getPickupStorageKey('Customer.A@example.com');
    const pickupB = getPickupStorageKey('Customer.B@example.com');
    expect(pickupA).toBe('lgb.pickup.u.customer.a_example.com.v1');
    expect(pickupB).toBe('lgb.pickup.u.customer.b_example.com.v1');
    expect(pickupA).not.toBe(pickupB);
    expect(pickupA).not.toBe(LEGACY_PICKUP_KEY);
  });
});
