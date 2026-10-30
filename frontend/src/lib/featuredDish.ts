import { API_BASE } from './orders';

export interface MenuItem {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  available: boolean;
  mostLoved: boolean;
}

export function filterAvailableDishes(items: unknown): MenuItem[] {
  if (!Array.isArray(items)) {
    return [];
  }

  return items
    .filter((item): item is Record<string, unknown> => {
      if (!item || typeof item !== 'object') {
        return false;
      }
      const candidate = item as Record<string, unknown>;
      const hasValidId = typeof candidate.id === 'string' && candidate.id.trim() !== '';
      const hasValidName = typeof candidate.name === 'string' && candidate.name.trim() !== '';
      const hasValidPrice = typeof candidate.price === 'number' && !Number.isNaN(candidate.price) && candidate.price >= 0;
      const isAvailable = candidate.available !== false;
      return hasValidId && hasValidName && hasValidPrice && isAvailable;
    })
    .map((item) => ({
      id: String(item.id),
      name: String(item.name).trim(),
      description: typeof item.description === 'string' ? item.description.trim() : '',
      price: Number(item.price),
      category: typeof item.category === 'string' ? item.category : '',
      image: typeof item.image === 'string' ? item.image : '',
      available: item.available !== false,
      mostLoved: Boolean(item.mostLoved),
    }));
}

export async function fetchFeaturedDishes(
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 4000
): Promise<MenuItem[]> {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

    // 1. Attempt to fetch specifically flagged "most-loved" items
    try {
      const res = await fetchImpl(`${API_BASE}/menu/most-loved`, { signal: controller.signal });
      if (res.ok) {
        const data = await res.json();
        const availableMostLoved = filterAvailableDishes(data);
        if (availableMostLoved.length > 0) {
          clearTimeout(timeoutId);
          return availableMostLoved;
        }
      }
    } catch {
      // If /most-loved throws or is unavailable, attempt general menu below if not timed out
    }

    if (controller.signal.aborted) {
      clearTimeout(timeoutId);
      return [];
    }

    // 2. If no "most-loved" items exist or endpoint failed, fall back to available items from main menu
    try {
      const menuRes = await fetchImpl(`${API_BASE}/menu`, { signal: controller.signal });
      clearTimeout(timeoutId);
      if (menuRes.ok) {
        const menuData = await menuRes.json();
        const availableItems = filterAvailableDishes(menuData);
        if (availableItems.length > 0) {
          return availableItems.slice(0, 5);
        }
      }
    } catch {
      clearTimeout(timeoutId);
      return [];
    }

    clearTimeout(timeoutId);
    return [];
  } catch {
    return [];
  }
}
