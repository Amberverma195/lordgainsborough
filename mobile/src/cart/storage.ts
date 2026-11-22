import { toAccountNamespace } from '../auth/accountNamespace';

export const LEGACY_CART_KEY = 'lgb.cart.v1';
export const LEGACY_PICKUP_KEY = 'lgb.pickup.v1';
export const LEGACY_GUEST_CART_KEY = 'lgb.cart.guest.v1';
export const LEGACY_GUEST_PICKUP_KEY = 'lgb.pickup.guest.v1';
export const GUEST_CART_KEY = 'lgb.cart.guest.v2';
export const GUEST_PICKUP_KEY = 'lgb.pickup.guest.v2';

export function getCartStorageKey(email?: string | null): string {
  const namespace = toAccountNamespace(email);
  if (namespace === 'guest') {
    return GUEST_CART_KEY;
  }
  return `lgb.cart.u.${namespace}.v2`;
}

export function getPickupStorageKey(email?: string | null): string {
  const namespace = toAccountNamespace(email);
  if (namespace === 'guest') {
    return GUEST_PICKUP_KEY;
  }
  return `lgb.pickup.u.${namespace}.v2`;
}

export function getCartPendingStorageKey(email?: string | null): string {
  const namespace = toAccountNamespace(email);
  return `lgb.cart.pending.${namespace}.v2`;
}

export function getLegacyV1CartStorageKey(email?: string | null): string {
  if (!email || !email.trim()) {
    return LEGACY_GUEST_CART_KEY;
  }
  const normalized = email.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
  return `lgb.cart.u.${normalized}.v1`;
}

export function getLegacyV1PickupStorageKey(email?: string | null): string {
  if (!email || !email.trim()) {
    return LEGACY_GUEST_PICKUP_KEY;
  }
  const normalized = email.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
  return `lgb.pickup.u.${normalized}.v1`;
}
