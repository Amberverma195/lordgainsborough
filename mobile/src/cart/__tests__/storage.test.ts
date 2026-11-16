import {
  GUEST_CART_KEY,
  GUEST_PICKUP_KEY,
  LEGACY_CART_KEY,
  LEGACY_PICKUP_KEY,
  getCartStorageKey,
  getPickupStorageKey,
} from '../storage';
import { toAccountNamespace } from '../../auth/accountNamespace';

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

  it('partitions keys per customer email, normalized to lowercase and collision-free hex namespace', () => {
    const keyA = getCartStorageKey('Customer.A@example.com');
    const keyB = getCartStorageKey('customer.b@example.com');
    const keyALower = getCartStorageKey('customer.a@example.com');

    const expectedNsA = toAccountNamespace('customer.a@example.com');
    const expectedNsB = toAccountNamespace('customer.b@example.com');

    expect(keyA).toBe(`lgb.cart.u.${expectedNsA}.v2`);
    expect(keyB).toBe(`lgb.cart.u.${expectedNsB}.v2`);
    expect(keyA).toBe(keyALower);
    expect(keyA).not.toBe(keyB);
    expect(keyA).not.toBe(LEGACY_CART_KEY);

    const pickupA = getPickupStorageKey('Customer.A@example.com');
    const pickupB = getPickupStorageKey('Customer.B@example.com');
    expect(pickupA).toBe(`lgb.pickup.u.${expectedNsA}.v2`);
    expect(pickupB).toBe(`lgb.pickup.u.${expectedNsB}.v2`);
    expect(pickupA).not.toBe(pickupB);
    expect(pickupA).not.toBe(LEGACY_PICKUP_KEY);
  });

  it('guarantees collision resistance between distinct emails such as alice+promo@example.com and alice_promo@example.com', () => {
    const cartPromoPlus = getCartStorageKey('alice+promo@example.com');
    const cartPromoUnder = getCartStorageKey('alice_promo@example.com');
    expect(cartPromoPlus).not.toBe(cartPromoUnder);

    const pickupPromoPlus = getPickupStorageKey('alice+promo@example.com');
    const pickupPromoUnder = getPickupStorageKey('alice_promo@example.com');
    expect(pickupPromoPlus).not.toBe(pickupPromoUnder);
  });
});
