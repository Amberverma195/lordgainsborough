import { apiRequest } from './client';
import {
  MenuCategory,
  MenuItem,
  PublicStoreSettings,
  parseCategoryList,
  parseMenuList,
  parseStoreSettings,
} from './parse';
import { ApiError } from './errors';

function malformed(message: string): ApiError {
  return new ApiError({ status: 200, code: 'MALFORMED_RESPONSE', message });
}

export async function fetchMenu(signal?: AbortSignal): Promise<MenuItem[]> {
  const response = await apiRequest('/api/menu', { signal });
  const parsed = parseMenuList(response.body);
  if (!parsed.ok) {
    throw malformed(parsed.message);
  }
  return parsed.value;
}

export async function fetchMostLoved(signal?: AbortSignal): Promise<MenuItem[]> {
  const response = await apiRequest('/api/menu/most-loved', { signal });
  const parsed = parseMenuList(response.body);
  if (!parsed.ok) {
    throw malformed(parsed.message);
  }
  return parsed.value;
}

export async function fetchCategories(signal?: AbortSignal): Promise<MenuCategory[]> {
  const response = await apiRequest('/api/categories', { signal });
  const parsed = parseCategoryList(response.body);
  if (!parsed.ok) {
    throw malformed(parsed.message);
  }
  return parsed.value;
}

export async function fetchStoreSettings(signal?: AbortSignal): Promise<PublicStoreSettings> {
  const response = await apiRequest('/api/store-settings', { signal });
  const parsed = parseStoreSettings(response.body);
  if (!parsed.ok) {
    throw malformed(parsed.message);
  }
  return parsed.value;
}

export function categoryFilters(categories: MenuCategory[], items: MenuItem[]): string[] {
  const names = new Set<string>();
  const ordered = [...categories].sort(
    (left, right) => left.displayOrder - right.displayOrder || left.name.localeCompare(right.name),
  );
  const filters: string[] = [];
  for (const category of ordered) {
    if (!names.has(category.name)) {
      names.add(category.name);
      filters.push(category.name);
    }
  }
  for (const item of items) {
    if (item.category && !names.has(item.category)) {
      names.add(item.category);
      filters.push(item.category);
    }
  }
  return filters;
}
