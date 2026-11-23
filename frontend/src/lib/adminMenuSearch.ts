export interface SearchableMenuItem {
  id?: string;
  name: string;
  description?: string;
  category?: string;
  price?: number;
  available?: boolean;
}

/**
 * Filter menu items by selected category pill and search query.
 * Matches search query case-insensitively across name, description, and category.
 */
export function filterAdminMenuItems<T extends SearchableMenuItem>(
  items: T[],
  categoryFilter: string,
  searchQuery: string
): T[] {
  if (!Array.isArray(items)) return [];

  let result = items;

  if (categoryFilter && categoryFilter !== 'All') {
    result = result.filter(item => item.category === categoryFilter);
  }

  const query = searchQuery ? searchQuery.trim().toLowerCase() : '';
  if (!query) {
    return result;
  }

  return result.filter(item => {
    const nameMatch = typeof item.name === 'string' && item.name.toLowerCase().includes(query);
    const descMatch = typeof item.description === 'string' && item.description.toLowerCase().includes(query);
    const catMatch = typeof item.category === 'string' && item.category.toLowerCase().includes(query);
    return nameMatch || descMatch || catMatch;
  });
}
