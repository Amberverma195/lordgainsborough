import { MenuItem } from '../api/parse';
import { CartLine, CartMutation, MAX_CART_LINES, MAX_ITEM_QUANTITY, MAX_TOTAL_QUANTITY, lineFromMenuItem } from './types';

function totalQuantity(cart: CartLine[]): number {
  return cart.reduce((sum, line) => sum + line.quantity, 0);
}

export function addToCart(cart: CartLine[], item: MenuItem, quantity = 1): CartMutation {
  if (!item.available) {
    return { cart, error: `${item.name} is currently unavailable.` };
  }
  if (!Number.isInteger(quantity) || quantity < 1) {
    return { cart, error: 'Choose a quantity of at least 1.' };
  }
  const existing = cart.find((line) => line.menuItemId === item.id);
  const nextQuantity = (existing?.quantity ?? 0) + quantity;
  if (nextQuantity > MAX_ITEM_QUANTITY) {
    return { cart, error: `You can order up to ${MAX_ITEM_QUANTITY} of each dish.` };
  }
  if (!existing && cart.length >= MAX_CART_LINES) {
    return { cart, error: `A pickup order can include up to ${MAX_CART_LINES} dishes.` };
  }
  if (totalQuantity(cart) - (existing?.quantity ?? 0) + nextQuantity > MAX_TOTAL_QUANTITY) {
    return { cart, error: `A pickup order can include up to ${MAX_TOTAL_QUANTITY} items.` };
  }
  if (!existing) {
    return { cart: [...cart, lineFromMenuItem(item, nextQuantity)], error: null };
  }
  return {
    cart: cart.map((line) => (line.menuItemId === item.id
      ? { ...lineFromMenuItem(item, nextQuantity) }
      : line)),
    error: null,
  };
}

export function setQuantity(cart: CartLine[], menuItemId: string, quantity: number): CartMutation {
  const existing = cart.find((line) => line.menuItemId === menuItemId);
  if (!existing) {
    return { cart, error: null };
  }
  if (quantity <= 0) {
    return { cart: cart.filter((line) => line.menuItemId !== menuItemId), error: null };
  }
  if (quantity > MAX_ITEM_QUANTITY) {
    return { cart, error: `You can order up to ${MAX_ITEM_QUANTITY} of each dish.` };
  }
  if (totalQuantity(cart) - existing.quantity + quantity > MAX_TOTAL_QUANTITY) {
    return { cart, error: `A pickup order can include up to ${MAX_TOTAL_QUANTITY} items.` };
  }
  return {
    cart: cart.map((line) => (line.menuItemId === menuItemId ? { ...line, quantity } : line)),
    error: null,
  };
}

export function removeFromCart(cart: CartLine[], menuItemId: string): CartLine[] {
  return cart.filter((line) => line.menuItemId !== menuItemId);
}

export function reconcileWithCatalog(cart: CartLine[], catalog: MenuItem[]): CartLine[] {
  const byId = new Map(catalog.map((item) => [item.id, item]));
  return cart.map((line) => {
    const item = byId.get(line.menuItemId);
    if (!item) {
      return { ...line, available: false };
    }
    return lineFromMenuItem(item, line.quantity);
  });
}

/**
 * Deterministic reconciliation between local and remote cart items.
 * If server is authoritative for existing lines (preserveServerQuantities: true),
 * server quantities take precedence to prevent resurrecting items decreased or
 * removed on another device.
 * Genuine local-only additions (e.g. items added offline) are preserved.
 */
export function mergeCarts(
  localCart: CartLine[],
  remoteCart: CartLine[],
  options?: { preserveServerQuantities?: boolean },
): CartLine[] {
  if (remoteCart.length === 0) {
    return localCart;
  }
  if (localCart.length === 0) {
    return remoteCart;
  }
  const merged = new Map<string, CartLine>();
  for (const line of remoteCart) {
    merged.set(line.menuItemId, { ...line });
  }
  for (const line of localCart) {
    const existing = merged.get(line.menuItemId);
    if (!existing) {
      merged.set(line.menuItemId, { ...line });
      continue;
    }
    if (!options?.preserveServerQuantities) {
      existing.quantity = Math.max(existing.quantity, line.quantity);
    }
  }
  return Array.from(merged.values());
}

/**
 * Merges guest items into an authenticated user's cart when transitioning from guest to logged-in.
 * Guest items that are already in the account cart have their quantities combined (capped at MAX_ITEM_QUANTITY).
 * Guest items not in the account cart are added.
 */
export function mergeGuestCart(guestCart: CartLine[], accountCart: CartLine[]): CartLine[] {
  if (guestCart.length === 0) {
    return accountCart;
  }
  if (accountCart.length === 0) {
    return guestCart;
  }
  const merged = new Map<string, CartLine>();
  for (const line of accountCart) {
    merged.set(line.menuItemId, { ...line });
  }
  for (const line of guestCart) {
    const existing = merged.get(line.menuItemId);
    if (!existing) {
      merged.set(line.menuItemId, { ...line });
    } else {
      existing.quantity = Math.min(MAX_ITEM_QUANTITY, existing.quantity + line.quantity);
    }
  }
  return Array.from(merged.values());
}

export function parseStoredCart(value: unknown): CartLine[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') {
      return [];
    }
    const line = entry as Partial<CartLine>;
    if (typeof line.menuItemId !== 'string' || typeof line.name !== 'string') {
      return [];
    }
    if (typeof line.unitPrice !== 'number' || typeof line.quantity !== 'number' || line.quantity < 1) {
      return [];
    }
    return [{
      menuItemId: line.menuItemId,
      name: line.name,
      description: typeof line.description === 'string' ? line.description : '',
      unitPrice: line.unitPrice,
      category: typeof line.category === 'string' ? line.category : '',
      image: typeof line.image === 'string' ? line.image : '',
      quantity: Math.min(MAX_ITEM_QUANTITY, Math.floor(line.quantity)),
      available: line.available !== false,
    }];
  });
}
