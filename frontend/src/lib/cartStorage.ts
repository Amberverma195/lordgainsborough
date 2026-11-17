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

export function mergeCartItems(first: CartItem[], second: CartItem[]): CartItem[] {
  const merged = new Map<string, CartItem>();

  for (const item of second) {
    if (item && item.id && item.quantity > 0) {
      merged.set(item.id, { ...item });
    }
  }

  for (const item of first) {
    if (item && item.id && item.quantity > 0) {
      const existing = merged.get(item.id);
      if (existing) {
        existing.quantity = Math.max(existing.quantity, item.quantity);
      } else {
        merged.set(item.id, { ...item });
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
    return parsed.filter(item => item && typeof item.id === 'string' && typeof item.quantity === 'number' && item.quantity > 0);
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
