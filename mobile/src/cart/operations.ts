import { MenuItem } from '../api/parse';
import { CartLine, CartMutation, MAX_CART_LINES, MAX_ITEM_QUANTITY, MAX_TOTAL_QUANTITY, lineFromMenuItem } from './types';

function totalQuantity(cart: CartLine[]): number {
  return cart.reduce((sum, line) => sum + line.quantity, 0);
}

/**
 * Enforces strict order limits on a cart:
 * - At most MAX_CART_LINES (30) distinct dishes
 * - At most MAX_ITEM_QUANTITY (20) per dish line
 * - At most MAX_TOTAL_QUANTITY (50) total items across all lines
 */
export function enforceCartLimits(cart: CartLine[]): CartLine[] {
  const result: CartLine[] = [];
  let currentTotalQuantity = 0;

  for (const line of cart) {
    if (!line || !line.menuItemId || typeof line.quantity !== 'number' || !Number.isFinite(line.quantity) || line.quantity <= 0) {
      continue;
    }
    if (result.length >= MAX_CART_LINES) {
      break;
    }
    if (currentTotalQuantity >= MAX_TOTAL_QUANTITY) {
      break;
    }
    const availableQuota = MAX_TOTAL_QUANTITY - currentTotalQuantity;
    const cappedQuantity = Math.min(Math.floor(line.quantity), MAX_ITEM_QUANTITY, availableQuota);
    if (cappedQuantity > 0) {
      result.push({
        ...line,
        quantity: cappedQuantity,
      });
      currentTotalQuantity += cappedQuantity;
    }
  }

  return result;
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
  const baseRemote = enforceCartLimits(remoteCart);
  if (localCart.length === 0) {
    return baseRemote;
  }
  const remoteTotal = baseRemote.reduce((sum, line) => sum + line.quantity, 0);
  let remainingQuota = Math.max(0, MAX_TOTAL_QUANTITY - remoteTotal);

  const merged = new Map<string, CartLine>();
  for (const line of baseRemote) {
    merged.set(line.menuItemId, { ...line });
  }

  for (const line of localCart) {
    if (!line || !line.menuItemId || typeof line.quantity !== 'number' || !Number.isFinite(line.quantity) || line.quantity <= 0) {
      continue;
    }
    const existing = merged.get(line.menuItemId);
    if (!existing) {
      if (merged.size < MAX_CART_LINES && remainingQuota > 0) {
        const toAdd = Math.min(Math.floor(line.quantity), MAX_ITEM_QUANTITY, remainingQuota);
        if (toAdd > 0) {
          merged.set(line.menuItemId, { ...line, quantity: toAdd });
          remainingQuota -= toAdd;
        }
      }
      continue;
    }
    if (!options?.preserveServerQuantities) {
      const targetQuantity = Math.min(MAX_ITEM_QUANTITY, Math.max(existing.quantity, Math.floor(line.quantity)));
      const delta = targetQuantity - existing.quantity;
      const allowedDelta = Math.min(delta, remainingQuota);
      if (allowedDelta > 0) {
        existing.quantity += allowedDelta;
        remainingQuota -= allowedDelta;
      }
    }
  }
  return Array.from(merged.values());
}

/**
 * Merges guest items into an authenticated user's cart when transitioning from guest to logged-in.
 * - Account cart items are preserved with priority.
 * - Overlapping items have their quantities combined (capped at MAX_ITEM_QUANTITY).
 * - New guest items are added up to MAX_CART_LINES (30 dishes).
 * - The merged total quantity across all items is strictly capped at MAX_TOTAL_QUANTITY (50 items).
 */
export function mergeGuestCart(guestCart: CartLine[], accountCart: CartLine[]): CartLine[] {
  const baseAccountCart = enforceCartLimits(accountCart);
  const accountTotal = baseAccountCart.reduce((sum, line) => sum + line.quantity, 0);
  let remainingQuota = Math.max(0, MAX_TOTAL_QUANTITY - accountTotal);

  const merged = new Map<string, CartLine>();
  for (const line of baseAccountCart) {
    merged.set(line.menuItemId, { ...line });
  }

  for (const line of guestCart) {
    if (!line || !line.menuItemId || typeof line.quantity !== 'number' || !Number.isFinite(line.quantity) || line.quantity <= 0) {
      continue;
    }
    const existing = merged.get(line.menuItemId);
    if (existing) {
      const maxAddable = Math.max(0, MAX_ITEM_QUANTITY - existing.quantity);
      const toAdd = Math.min(Math.floor(line.quantity), maxAddable, remainingQuota);
      if (toAdd > 0) {
        existing.quantity += toAdd;
        remainingQuota -= toAdd;
      }
    } else {
      if (merged.size < MAX_CART_LINES && remainingQuota > 0) {
        const toAdd = Math.min(Math.floor(line.quantity), MAX_ITEM_QUANTITY, remainingQuota);
        if (toAdd > 0) {
          merged.set(line.menuItemId, { ...line, quantity: toAdd });
          remainingQuota -= toAdd;
        }
      }
    }
  }

  return Array.from(merged.values());
}

export function parseStoredCart(value: unknown): CartLine[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const lines: CartLine[] = value.flatMap((entry) => {
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
  return enforceCartLimits(lines);
}
