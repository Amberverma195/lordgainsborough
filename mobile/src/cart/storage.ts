export const LEGACY_CART_KEY = 'lgb.cart.v1';
export const LEGACY_PICKUP_KEY = 'lgb.pickup.v1';
export const GUEST_CART_KEY = 'lgb.cart.guest.v1';
export const GUEST_PICKUP_KEY = 'lgb.pickup.guest.v1';

export function getCartStorageKey(email?: string | null): string {
  if (!email || !email.trim()) {
    return GUEST_CART_KEY;
  }
  const normalized = email.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
  return `lgb.cart.u.${normalized}.v1`;
}

export function getPickupStorageKey(email?: string | null): string {
  if (!email || !email.trim()) {
    return GUEST_PICKUP_KEY;
  }
  const normalized = email.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
  return `lgb.pickup.u.${normalized}.v1`;
}
