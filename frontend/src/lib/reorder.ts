import { CartItem } from '@/context/CartContext';
import { MenuItem } from '@/lib/mockData';

export interface OrderItemSource {
  menuItemId?: string;
  name?: string;
  quantity?: number;
  unitPrice?: number;
  price?: number;
}

export interface ReorderOptions {
  /**
   * If true, permits substituting an item with the same name when its original
   * product ID no longer exists on the current menu.
   * Defaults to false to enforce strict identity by menuItemId / id.
   */
  allowNameFallback?: boolean;

  /**
   * List or set of historical product IDs or dish names that the customer has
   * explicitly confirmed for name-based substitution.
   */
  confirmedSubstitutions?: string[] | Set<string>;
}

export interface ReorderSubstitutionCandidate {
  historicalId?: string;
  historicalName: string;
  matchedItem: MenuItem;
}

export interface ReorderPlan {
  availableItems: CartItem[];
  unavailableCount: number;
  priceChangedCount: number;
  notices: string[];
  /**
   * Items whose original product IDs disappeared or changed, but a current
   * menu item with the same name was found. Kept separate because strict identity
   * prevents silent substitution without customer confirmation.
   */
  unconfirmedSubstitutions?: ReorderSubstitutionCandidate[];
}

export const REORDER_NOTICE_STORAGE_KEY = 'lord_gainsborough_reorder_notice';

/**
 * Matches historical order items against the authoritative current menu.
 * Enforces strict identity by menuItemId / id by default to prevent silent
 * substitution of retired items that happen to share a name with a current dish.
 * Discards discontinued or unavailable items, refreshes item details (name, description,
 * category, image, price) to current values, and produces descriptive notices.
 */
export function planReorder(
  historicalItems: OrderItemSource[],
  currentMenu: MenuItem[],
  options?: ReorderOptions
): ReorderPlan {
  const availableItems: CartItem[] = [];
  const notices: string[] = [];
  const unconfirmedSubstitutions: ReorderSubstitutionCandidate[] = [];
  let unavailableCount = 0;
  let priceChangedCount = 0;

  if (!Array.isArray(historicalItems) || !Array.isArray(currentMenu)) {
    return { availableItems: [], unavailableCount: 0, priceChangedCount: 0, notices: [] };
  }

  const confirmedSet = new Set<string>();
  if (options?.confirmedSubstitutions) {
    if (Array.isArray(options.confirmedSubstitutions)) {
      for (const s of options.confirmedSubstitutions) {
        if (typeof s === 'string' && s.trim()) confirmedSet.add(s.trim().toLowerCase());
      }
    } else if (options.confirmedSubstitutions instanceof Set) {
      for (const s of options.confirmedSubstitutions) {
        if (typeof s === 'string' && s.trim()) confirmedSet.add(s.trim().toLowerCase());
      }
    }
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

    // Strict identity: match strictly by unique item ID
    let menuItem = historicalId ? currentMenu.find(m => m.id === historicalId) : undefined;
    let isSubstituted = false;

    // If ID is missing or no longer exists on the current menu, check for a name match
    if (!menuItem && historicalName) {
      const nameMatch = currentMenu.find(
        m => m.name.trim().toLowerCase() === historicalName.toLowerCase()
      );

      if (nameMatch) {
        const isExplicitlyConfirmed =
          options?.allowNameFallback === true ||
          (historicalId && confirmedSet.has(historicalId.toLowerCase())) ||
          confirmedSet.has(historicalName.toLowerCase());

        if (isExplicitlyConfirmed) {
          menuItem = nameMatch;
          isSubstituted = true;
        } else {
          // Record candidate for visibility, but do NOT substitute silently
          unconfirmedSubstitutions.push({
            historicalId: historicalId || undefined,
            historicalName,
            matchedItem: nameMatch,
          });
        }
      }
    }

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

    if (isSubstituted) {
      notices.push(
        `"${menuItem.name}" was substituted with current menu item upon customer confirmation.`
      );
    }

    if (typeof menuItem.price === 'number' && menuItem.price !== historicalPrice) {
      priceChangedCount++;
      notices.push(
        `"${menuItem.name}" price updated from $${historicalPrice.toFixed(2)} to current menu price $${menuItem.price.toFixed(2)}.`
      );
    }

    const existing = availableItems.find(i => i.id === menuItem!.id);
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
    unconfirmedSubstitutions:
      unconfirmedSubstitutions.length > 0 ? unconfirmedSubstitutions : undefined,
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
