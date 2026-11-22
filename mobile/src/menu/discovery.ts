import { MenuItem, OrderLine } from '../api/parse';
export function filterDishes(
  items: MenuItem[],
  search: string,
  category: string,
  favourites?: string[],
) {
  const query = search.trim().toLocaleLowerCase();
  return items.filter(
    (item) =>
      (category === 'All' || item.category === category) &&
      (!query ||
        (item.name + ' ' + item.description)
          .toLocaleLowerCase()
          .includes(query)) &&
      (!favourites || favourites.includes(item.id)),
  );
}
export interface ReorderOptions {
  allowNameFallback?: boolean;
  confirmedSubstitutions?: string[] | Set<string>;
}

export function planReorder(
  lines: OrderLine[],
  menu: MenuItem[],
  options?: ReorderOptions,
) {
  const available: { item: MenuItem; quantity: number }[] = [];
  const notices: string[] = [];

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

  for (const line of lines) {
    let item = menu.find((item) => item.id === line.menuItemId);
    let isSubstituted = false;

    // Strict identity by default: do not silently substitute by name if product ID disappeared
    if (!item && line.name) {
      const nameMatch = menu.find(
        (m) => m.name.trim().toLowerCase() === line.name.trim().toLowerCase(),
      );
      if (nameMatch) {
        const isExplicitlyConfirmed =
          options?.allowNameFallback === true ||
          (line.menuItemId && confirmedSet.has(line.menuItemId.toLowerCase())) ||
          confirmedSet.has(line.name.trim().toLowerCase());

        if (isExplicitlyConfirmed) {
          item = nameMatch;
          isSubstituted = true;
        }
      }
    }

    if (!item || !item.available) {
      notices.push(line.name + ' is unavailable or discontinued.');
      continue;
    }
    if (isSubstituted) {
      notices.push(
        item.name + ' substituted for retired item per customer confirmation.',
      );
    }
    if (item.price !== line.unitPrice || item.name !== line.name)
      notices.push(item.name + ' has changed; current price will be used.');
    available.push({ item, quantity: line.quantity });
  }
  return { available, notices };
}
