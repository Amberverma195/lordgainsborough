import { describe, expect, it } from 'vitest';
import { filterAdminMenuItems, SearchableMenuItem } from './adminMenuSearch';

describe('filterAdminMenuItems', () => {
  const sampleItems: SearchableMenuItem[] = [
    {
      id: '1',
      name: 'Cheesy Garlic Bread',
      description: 'Garlic butter, cheddar, and mozzarella melted over toasted baguette',
      category: 'Appetizers',
      price: 13.99,
      available: true,
    },
    {
      id: '2',
      name: 'Mozzarella Sticks',
      description: 'Six breaded mozzarella sticks, deep-fried until golden',
      category: 'Appetizers',
      price: 14.99,
      available: true,
    },
    {
      id: '3',
      name: 'Gainsborough Deluxe Burger',
      description: 'Handmade beef patty with cheddar, bacon, and house burger sauce',
      category: 'Homemade Burgers',
      price: 18.5,
      available: true,
    },
    {
      id: '4',
      name: 'Caesar Salad',
      description: 'Crisp romaine, creamy garlic dressing, herb croutons, and bacon bits',
      category: 'Salads',
      price: 12.99,
      available: false,
    },
    {
      id: '5',
      name: 'Pepperoni Pizza',
      description: 'Classic stone-baked pizza with tomato sauce, mozzarella, and dry-cured pepperoni',
      category: 'Pizzas',
      price: 21.0,
      available: true,
    },
  ];

  it('returns all items when category is "All" and query is empty', () => {
    const results = filterAdminMenuItems(sampleItems, 'All', '');
    expect(results).toHaveLength(5);
  });

  it('filters by category when query is empty', () => {
    const results = filterAdminMenuItems(sampleItems, 'Appetizers', '');
    expect(results).toHaveLength(2);
    expect(results.map(i => i.name)).toEqual(['Cheesy Garlic Bread', 'Mozzarella Sticks']);
  });

  it('searches by name case-insensitively', () => {
    const results = filterAdminMenuItems(sampleItems, 'All', 'burger');
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('Gainsborough Deluxe Burger');

    const upper = filterAdminMenuItems(sampleItems, 'All', 'CHEESY');
    expect(upper).toHaveLength(1);
    expect(upper[0].name).toBe('Cheesy Garlic Bread');
  });

  it('searches by ingredient or description text', () => {
    // "garlic" appears in Cheesy Garlic Bread description and Caesar Salad description
    const results = filterAdminMenuItems(sampleItems, 'All', 'garlic');
    expect(results).toHaveLength(2);
    expect(results.map(i => i.name)).toEqual(['Cheesy Garlic Bread', 'Caesar Salad']);
  });

  it('searches by category name', () => {
    const results = filterAdminMenuItems(sampleItems, 'All', 'Salads');
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('Caesar Salad');
  });

  it('combines category filter pill with search query', () => {
    // Searching "garlic" in "Appetizers" should only return Cheesy Garlic Bread, not Caesar Salad
    const results = filterAdminMenuItems(sampleItems, 'Appetizers', 'garlic');
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('Cheesy Garlic Bread');

    // Searching "garlic" in "Pizzas" should return empty
    const pizzaGarlic = filterAdminMenuItems(sampleItems, 'Pizzas', 'garlic');
    expect(pizzaGarlic).toHaveLength(0);
  });

  it('handles whitespace-only queries by ignoring leading/trailing spaces', () => {
    const whitespaceOnly = filterAdminMenuItems(sampleItems, 'All', '   ');
    expect(whitespaceOnly).toHaveLength(5);

    const trimmedSearch = filterAdminMenuItems(sampleItems, 'All', '   deluxe   ');
    expect(trimmedSearch).toHaveLength(1);
    expect(trimmedSearch[0].name).toBe('Gainsborough Deluxe Burger');
  });

  it('returns empty array when no items match', () => {
    const results = filterAdminMenuItems(sampleItems, 'All', 'sushi');
    expect(results).toHaveLength(0);
  });

  it('gracefully handles empty, null or missing fields', () => {
    const partialItem: SearchableMenuItem = {
      name: 'Plain Water',
    };
    expect(filterAdminMenuItems([partialItem], 'All', 'water')).toHaveLength(1);
    expect(filterAdminMenuItems([partialItem], 'All', 'drink')).toHaveLength(0);
    expect(filterAdminMenuItems([], 'All', 'burger')).toEqual([]);
  });
});
