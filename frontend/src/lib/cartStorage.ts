import { MenuItem } from './mockData';
import { toAccountNamespace } from './accountNamespace';

export interface CartItem extends MenuItem {
  quantity: number;
}

export interface ServerCartItem {
  menuItemId: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  quantity: number;
}

export const LEGACY_GLOBAL_CART_STORAGE_KEY = 'lord_gainsborough_cart';
export const GUEST_CART_STORAGE_KEY = 'lord_gainsborough_cart_guest';
export const GENUINE_GUEST_FLAG_STORAGE_KEY = 'lord_gainsborough_has_guest_cart';
export const ACCOUNT_CART_STORAGE_PREFIX = 'lord_gainsborough_cart_';

export function getAccountCartStorageKey(email: string): string {
  const ns = toAccountNamespace(email);
  return `${ACCOUNT_CART_STORAGE_PREFIX}${ns}`;
}

export function isAccountCartStorageKey(key: string): boolean {
  return key.startsWith(ACCOUNT_CART_STORAGE_PREFIX) && key !== GUEST_CART_STORAGE_KEY && key !== LEGACY_GLOBAL_CART_STORAGE_KEY;
}

export function toServerItems(items: CartItem[]): ServerCartItem[] {
  return items.map(item => ({
    menuItemId: item.id,
    name: item.name,
    description: item.description,
    price: item.price,
    category: item.category,
    image: item.image,
    quantity: item.quantity,
  }));
}

export function fromServerItems(items: ServerCartItem[]): CartItem[] {
  return items.map(item => ({
    id: item.menuItemId,
    name: item.name,
    description: item.description,
    price: item.price,
    category: item.category,
    image: item.image,
    quantity: item.quantity,
  }));
}

export const MAX_ITEM_QUANTITY = 20;
export const MAX_CART_LINES = 30;
export const MAX_TOTAL_QUANTITY = 50;

/**
 * Enforces strict order limits on a cart:
 * - At most MAX_CART_LINES (30) distinct dishes
 * - At most MAX_ITEM_QUANTITY (20) per dish line
 * - At most MAX_TOTAL_QUANTITY (50) total items across all lines
 */
export function enforceCartLimits(items: CartItem[]): CartItem[] {
  const result: CartItem[] = [];
  let currentTotalQuantity = 0;

  for (const item of items) {
    if (!item || !item.id || typeof item.quantity !== 'number' || !Number.isFinite(item.quantity) || item.quantity <= 0) {
      continue;
    }
    if (result.length >= MAX_CART_LINES) {
      break;
    }
    if (currentTotalQuantity >= MAX_TOTAL_QUANTITY) {
      break;
    }
    const availableQuota = MAX_TOTAL_QUANTITY - currentTotalQuantity;
    const cappedQuantity = Math.min(Math.floor(item.quantity), MAX_ITEM_QUANTITY, availableQuota);
    if (cappedQuantity > 0) {
      result.push({
        ...item,
        quantity: cappedQuantity,
      });
      currentTotalQuantity += cappedQuantity;
    }
  }

  return result;
}

export function mergeCartItems(
  first: CartItem[],
  second: CartItem[],
  options?: { combineQuantities?: boolean },
): CartItem[] {
  const baseCart = enforceCartLimits(second);
  const accountTotal = baseCart.reduce((sum, item) => sum + item.quantity, 0);
  let remainingQuota = Math.max(0, MAX_TOTAL_QUANTITY - accountTotal);

  const merged = new Map<string, CartItem>();
  for (const item of baseCart) {
    merged.set(item.id, { ...item });
  }

  for (const item of first) {
    if (!item || !item.id || typeof item.quantity !== 'number' || !Number.isFinite(item.quantity) || item.quantity <= 0) {
      continue;
    }
    const existing = merged.get(item.id);
    if (existing) {
      if (options?.combineQuantities) {
        const maxAddable = Math.max(0, MAX_ITEM_QUANTITY - existing.quantity);
        const toAdd = Math.min(Math.floor(item.quantity), maxAddable, remainingQuota);
        if (toAdd > 0) {
          existing.quantity += toAdd;
          remainingQuota -= toAdd;
        }
      } else {
        const targetQuantity = Math.min(MAX_ITEM_QUANTITY, Math.max(existing.quantity, Math.floor(item.quantity)));
        const delta = targetQuantity - existing.quantity;
        const allowedDelta = Math.min(delta, remainingQuota);
        if (allowedDelta > 0) {
          existing.quantity += allowedDelta;
          remainingQuota -= allowedDelta;
        }
      }
    } else {
      if (merged.size < MAX_CART_LINES && remainingQuota > 0) {
        const toAdd = Math.min(Math.floor(item.quantity), MAX_ITEM_QUANTITY, remainingQuota);
        if (toAdd > 0) {
          merged.set(item.id, { ...item, quantity: toAdd });
          remainingQuota -= toAdd;
        }
      }
    }
  }

  return Array.from(merged.values());
}

export function purgeLegacyGlobalCart(): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.removeItem(LEGACY_GLOBAL_CART_STORAGE_KEY);
  } catch {
    // ignore
  }
}

function safeParseCart(raw: string | null): CartItem[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const valid = parsed.filter(item => item && typeof item.id === 'string' && typeof item.quantity === 'number' && item.quantity > 0);
    return enforceCartLimits(valid);
  } catch {
    return [];
  }
}

export function loadGuestCart(): CartItem[] {
  if (typeof window === 'undefined') return [];
  try {
    return safeParseCart(localStorage.getItem(GUEST_CART_STORAGE_KEY));
  } catch {
    return [];
  }
}

export function saveGuestCart(items: CartItem[]): void {
  if (typeof window === 'undefined') return;
  try {
    purgeLegacyGlobalCart();
    if (items.length > 0) {
      localStorage.setItem(GUEST_CART_STORAGE_KEY, JSON.stringify(items));
      localStorage.setItem(GENUINE_GUEST_FLAG_STORAGE_KEY, 'true');
    } else {
      localStorage.removeItem(GUEST_CART_STORAGE_KEY);
      localStorage.removeItem(GENUINE_GUEST_FLAG_STORAGE_KEY);
    }
  } catch {
    // ignore
  }
}

export function clearGuestCart(): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.removeItem(GUEST_CART_STORAGE_KEY);
    localStorage.removeItem(GENUINE_GUEST_FLAG_STORAGE_KEY);
    purgeLegacyGlobalCart();
  } catch {
    // ignore
  }
}

export function isGenuineGuestCart(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    const flag = localStorage.getItem(GENUINE_GUEST_FLAG_STORAGE_KEY);
    if (flag !== 'true') return false;
    const items = loadGuestCart();
    return items.length > 0;
  } catch {
    return false;
  }
}

export function setGenuineGuestCartFlag(active: boolean): void {
  if (typeof window === 'undefined') return;
  try {
    if (active) {
      localStorage.setItem(GENUINE_GUEST_FLAG_STORAGE_KEY, 'true');
    } else {
      localStorage.removeItem(GENUINE_GUEST_FLAG_STORAGE_KEY);
    }
  } catch {
    // ignore
  }
}

export function loadAccountCart(email: string): CartItem[] {
  if (typeof window === 'undefined' || !email) return [];
  try {
    const key = getAccountCartStorageKey(email);
    return safeParseCart(localStorage.getItem(key));
  } catch {
    return [];
  }
}

export function saveAccountCart(email: string, items: CartItem[]): void {
  if (typeof window === 'undefined' || !email) return;
  try {
    purgeLegacyGlobalCart();
    const key = getAccountCartStorageKey(email);
    if (items.length > 0) {
      localStorage.setItem(key, JSON.stringify(items));
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    // ignore
  }
}

export function clearAccountCart(email: string): void {
  if (typeof window === 'undefined' || !email) return;
  try {
    localStorage.removeItem(getAccountCartStorageKey(email));
  } catch {
    // ignore
  }
}
