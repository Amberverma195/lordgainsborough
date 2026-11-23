import { describe, it, expect } from 'vitest';
import { sortMenuItems, SortableMenuItem, SortableCategory } from './menuSort';

describe('menuSort - deterministic menu ordering', () => {
  const sampleCategories: SortableCategory[] = [
    { id: '1', name: 'Appetizers', displayOrder: 1 },
    { id: '2', name: 'Pizzas', displayOrder: 2 },
    { id: '3', name: 'Salads', displayOrder: 3 },
  ];

  it('keeps menu item position completely unchanged when availability is toggled', () => {
    const items: SortableMenuItem[] = [
      { id: 'item-1', name: 'Cheesy Garlic Bread', category: 'Appetizers', available: true },
      { id: 'item-2', name: 'Mozzarella Sticks', category: 'Appetizers', available: true },
      { id: 'item-3', name: 'Cheese Pizza (10")', category: 'Pizzas', available: true },
      { id: 'item-4', name: 'House Salad (Small)', category: 'Salads', available: true },
    ];

    const sortedBefore = sortMenuItems(items, sampleCategories);
    expect(sortedBefore.map(i => i.id)).toEqual(['item-1', 'item-2', 'item-3', 'item-4']);

    // Toggle item-2 (Mozzarella Sticks) to unavailable
    const itemsWithUnavailable = items.map(item =>
      item.id === 'item-2' ? { ...item, available: false } : item
    );

    const sortedAfter = sortMenuItems(itemsWithUnavailable, sampleCategories);
    // Order of IDs MUST be identical
    expect(sortedAfter.map(i => i.id)).toEqual(['item-1', 'item-2', 'item-3', 'item-4']);
    expect(sortedAfter[1].available).toBe(false);

    // Toggle first item to unavailable
    const itemsWithFirstUnavailable = items.map(item =>
      item.id === 'item-1' ? { ...item, available: false } : item
    );
    const sortedFirst = sortMenuItems(itemsWithFirstUnavailable, sampleCategories);
    expect(sortedFirst.map(i => i.id)).toEqual(['item-1', 'item-2', 'item-3', 'item-4']);

    // Toggle last item to unavailable
    const itemsWithLastUnavailable = items.map(item =>
      item.id === 'item-4' ? { ...item, available: false } : item
    );
    const sortedLast = sortMenuItems(itemsWithLastUnavailable, sampleCategories);
    expect(sortedLast.map(i => i.id)).toEqual(['item-1', 'item-2', 'item-3', 'item-4']);
  });

  it('orders items primarily by category display order', () => {
    const mixedItems: SortableMenuItem[] = [
      { id: 'salad', name: 'House Salad (Small)', category: 'Salads' },
      { id: 'app', name: 'Cheesy Garlic Bread', category: 'Appetizers' },
      { id: 'pizza', name: 'Cheese Pizza (10")', category: 'Pizzas' },
    ];

    const sorted = sortMenuItems(mixedItems, sampleCategories);
    expect(sorted.map(i => i.id)).toEqual(['app', 'pizza', 'salad']);
  });

  it('orders items within category according to canonical specification order', () => {
    const appItems: SortableMenuItem[] = [
      { id: '4', name: 'Pickle Spears', category: 'Appetizers' },
      { id: '1', name: 'Cheesy Garlic Bread', category: 'Appetizers' },
      { id: '3', name: 'Mozzarella Sticks', category: 'Appetizers' },
      { id: '2', name: 'Jalapeno Poppers', category: 'Appetizers' },
    ];

    const sorted = sortMenuItems(appItems, sampleCategories);
    expect(sorted.map(i => i.name)).toEqual([
      'Cheesy Garlic Bread',
      'Jalapeno Poppers',
      'Mozzarella Sticks',
      'Pickle Spears',
    ]);
  });

  it('places unseeded custom items deterministically within their category', () => {
    const items: SortableMenuItem[] = [
      { id: 'custom-b', name: 'Zucchini Fritters', category: 'Appetizers' },
      { id: 'seed-1', name: 'Cheesy Garlic Bread', category: 'Appetizers' },
      { id: 'custom-a', name: 'Artichoke Dip', category: 'Appetizers' },
    ];

    const sorted = sortMenuItems(items, sampleCategories);
    // Seed item first, then custom items alphabetically: Artichoke Dip, Zucchini Fritters
    expect(sorted.map(i => i.name)).toEqual([
      'Cheesy Garlic Bread',
      'Artichoke Dip',
      'Zucchini Fritters',
    ]);
  });

  it('breaks ties on identical names using deterministic ID sort', () => {
    const items: SortableMenuItem[] = [
      { id: 'id-z', name: 'Special Soup', category: 'Appetizers' },
      { id: 'id-a', name: 'Special Soup', category: 'Appetizers' },
      { id: 'id-m', name: 'Special Soup', category: 'Appetizers' },
    ];

    const sorted = sortMenuItems(items, sampleCategories);
    expect(sorted.map(i => i.id)).toEqual(['id-a', 'id-m', 'id-z']);
  });

  it('handles empty and single element arrays gracefully', () => {
    expect(sortMenuItems([])).toEqual([]);
    const single = [{ id: '1', name: 'Coffee', category: 'Beverages' }];
    expect(sortMenuItems(single)).toEqual(single);
  });
});
