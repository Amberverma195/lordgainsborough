import { CartItem } from '@/context/CartContext';
import { MenuItem } from '@/lib/mockData';

export interface OrderItemSource {
  menuItemId?: string;
  name?: string;
  quantity?: number;
  unitPrice?: number;
  price?: number;
}

export interface ReorderPlan {
  availableItems: CartItem[];
  unavailableCount: number;
  priceChangedCount: number;
  notices: string[];
}

export const REORDER_NOTICE_STORAGE_KEY = 'lord_gainsborough_reorder_notice';

/**
 * Matches historical order items against the authoritative current menu.
 * Discards discontinued or unavailable items, refreshes item details (name, description,
 * category, image, price) to current values, and produces descriptive notices.
 */
export function planReorder(
  historicalItems: OrderItemSource[],
  currentMenu: MenuItem[]
): ReorderPlan {
  const availableItems: CartItem[] = [];
  const notices: string[] = [];
  let unavailableCount = 0;
  let priceChangedCount = 0;

  if (!Array.isArray(historicalItems) || !Array.isArray(currentMenu)) {
    return { availableItems: [], unavailableCount: 0, priceChangedCount: 0, notices: [] };
  }

  for (const item of historicalItems) {
    if (!item) continue;
    const historicalName = (item.name || '').trim();
    const historicalId = (item.menuItemId || '').trim();
    const historicalPrice =
      typeof item.unitPrice === 'number'
        ? item.unitPrice
        : typeof item.price === 'number'
        ? item.price
        : 0;
    const quantity = Math.max(1, Math.floor(Number(item.quantity) || 1));

    // Match by ID first; fallback to case-insensitive name
    const menuItem =
      (historicalId ? currentMenu.find(m => m.id === historicalId) : undefined) ||
      (historicalName ? currentMenu.find(m => m.name.trim().toLowerCase() === historicalName.toLowerCase()) : undefined);

    if (!menuItem) {
      unavailableCount++;
      notices.push(`"${historicalName || 'Item'}" is no longer on the menu.`);
      continue;
    }

    if (menuItem.available === false) {
      unavailableCount++;
      notices.push(`"${menuItem.name}" is currently unavailable.`);
      continue;
    }

    if (typeof menuItem.price === 'number' && menuItem.price !== historicalPrice) {
      priceChangedCount++;
      notices.push(
        `"${menuItem.name}" price updated from $${historicalPrice.toFixed(2)} to current menu price $${menuItem.price.toFixed(2)}.`
      );
    }

    const existing = availableItems.find(i => i.id === menuItem.id);
    if (existing) {
      existing.quantity += quantity;
    } else {
      availableItems.push({
        id: menuItem.id,
        name: menuItem.name,
        description: menuItem.description || '',
        category: menuItem.category || 'Mains',
        image: menuItem.image || '',
        price: menuItem.price,
        quantity,
      });
    }
  }

  return {
    availableItems,
    unavailableCount,
    priceChangedCount,
    notices,
  };
}

function getSessionStorage(): Storage | undefined {
  try {
    if (typeof sessionStorage !== 'undefined') return sessionStorage;
    if (typeof window !== 'undefined' && window.sessionStorage) return window.sessionStorage;
  } catch {
    // Storage access restricted
  }
  return undefined;
}

export function storeReorderNotice(notice: string): void {
  const store = getSessionStorage();
  if (!store) return;
  try {
    store.setItem(REORDER_NOTICE_STORAGE_KEY, notice);
  } catch {
    // Private mode or storage quota
  }
}

export function consumeReorderNotice(): string | null {
  const store = getSessionStorage();
  if (!store) return null;
  try {
    const notice = store.getItem(REORDER_NOTICE_STORAGE_KEY);
    if (notice) {
      store.removeItem(REORDER_NOTICE_STORAGE_KEY);
      return notice;
    }
  } catch {
    // Private mode
  }
  return null;
}
