import { afterEach, describe, expect, it, vi } from 'vitest';
import { MenuItem } from './mockData';
import { consumeReorderNotice, planReorder, storeReorderNotice } from './reorder';

describe('Website Reorder - Menu Refresh & Price Modernization', () => {
  const currentMenu: MenuItem[] = [
    {
      id: 'dish-1',
      name: 'Truffle Burrata',
      description: 'Creamy burrata, shaved black truffle, heirloom tomatoes.',
      price: 22.0,
      category: 'Starters',
      image: '/menu/burrata.jpg',
      available: true,
    },
    {
      id: 'dish-2',
      name: 'Wagyu Beef Wellington',
      description: 'Grade A5 Wagyu, mushroom duxelles, puff pastry.',
      price: 75.0,
      category: 'Mains',
      image: '/menu/wellington.jpg',
      available: true,
    },
    {
      id: 'dish-3',
      name: 'Seasonal Catch',
      description: 'Wild sea bass with seasonal greens.',
      price: 38.0,
      category: 'Mains',
      image: '/menu/seabass.jpg',
      available: false, // Currently sold out
    },
  ];

  it('refreshes historical item prices and details to current menu values', () => {
    const historicalOrderItems = [
      {
        menuItemId: 'dish-1',
        name: 'Truffle Burrata',
        quantity: 2,
        unitPrice: 18.0, // Historical price from months ago
      },
      {
        menuItemId: 'dish-2',
        name: 'Wagyu Beef Wellington',
        quantity: 1,
        unitPrice: 65.0, // Historical price
      },
    ];

    const plan = planReorder(historicalOrderItems, currentMenu);

    expect(plan.availableItems).toHaveLength(2);
    expect(plan.unavailableCount).toBe(0);
    expect(plan.priceChangedCount).toBe(2);

    // Verifies prices are updated to current menu prices
    expect(plan.availableItems[0].price).toBe(22.0);
    expect(plan.availableItems[0].quantity).toBe(2);
    expect(plan.availableItems[0].description).toBe('Creamy burrata, shaved black truffle, heirloom tomatoes.');
    expect(plan.availableItems[0].category).toBe('Starters');
    expect(plan.availableItems[0].image).toBe('/menu/burrata.jpg');

    expect(plan.availableItems[1].price).toBe(75.0);
    expect(plan.availableItems[1].quantity).toBe(1);

    // Notices reflect updated prices
    expect(plan.notices).toHaveLength(2);
    expect(plan.notices[0]).toContain('price updated from $18.00 to current menu price $22.00');
    expect(plan.notices[1]).toContain('price updated from $65.00 to current menu price $75.00');
  });

  it('excludes discontinued dishes no longer on the current menu', () => {
    const historicalOrderItems = [
      {
        menuItemId: 'dish-legacy',
        name: 'Discontinued Garlic Bread',
        quantity: 1,
        unitPrice: 8.0,
      },
      {
        menuItemId: 'dish-1',
        name: 'Truffle Burrata',
        quantity: 1,
        unitPrice: 22.0,
      },
    ];

    const plan = planReorder(historicalOrderItems, currentMenu);

    expect(plan.availableItems).toHaveLength(1);
    expect(plan.availableItems[0].id).toBe('dish-1');
    expect(plan.unavailableCount).toBe(1);
    expect(plan.notices).toEqual(['"Discontinued Garlic Bread" is no longer on the menu.']);
  });

  it('excludes dishes marked as currently unavailable (available: false)', () => {
    const historicalOrderItems = [
      {
        menuItemId: 'dish-3',
        name: 'Seasonal Catch',
        quantity: 1,
        unitPrice: 38.0,
      },
    ];

    const plan = planReorder(historicalOrderItems, currentMenu);

    expect(plan.availableItems).toHaveLength(0);
    expect(plan.unavailableCount).toBe(1);
    expect(plan.notices).toEqual(['"Seasonal Catch" is currently unavailable.']);
  });

  it('enforces strict identity: does not silently substitute items by name when old product ID disappears', () => {
    const historicalOrderItems = [
      {
        menuItemId: 'old-random-uuid',
        name: 'truffle burrata',
        quantity: 3,
        unitPrice: 20.0,
      },
    ];

    // By default, strict identity is enforced; silent name fallback is rejected
    const plan = planReorder(historicalOrderItems, currentMenu);

    expect(plan.availableItems).toHaveLength(0);
    expect(plan.unavailableCount).toBe(1);
    expect(plan.notices).toEqual(['"truffle burrata" is no longer on the menu.']);
    expect(plan.unconfirmedSubstitutions).toBeDefined();
    expect(plan.unconfirmedSubstitutions).toHaveLength(1);
    expect(plan.unconfirmedSubstitutions![0].historicalId).toBe('old-random-uuid');
    expect(plan.unconfirmedSubstitutions![0].matchedItem.id).toBe('dish-1');
  });

  it('allows name-based substitution when customer explicitly confirms substitution by ID or name', () => {
    const historicalOrderItems = [
      {
        menuItemId: 'old-random-uuid',
        name: 'truffle burrata',
        quantity: 3,
        unitPrice: 20.0,
      },
    ];

    // 1. Confirmed by historical product ID
    const planById = planReorder(historicalOrderItems, currentMenu, {
      confirmedSubstitutions: ['old-random-uuid'],
    });
    expect(planById.availableItems).toHaveLength(1);
    expect(planById.availableItems[0].id).toBe('dish-1');
    expect(planById.availableItems[0].name).toBe('Truffle Burrata');
    expect(planById.availableItems[0].price).toBe(22.0);
    expect(planById.availableItems[0].quantity).toBe(3);
    expect(planById.notices).toContain(
      '"Truffle Burrata" was substituted with current menu item upon customer confirmation.'
    );

    // 2. Confirmed by dish name
    const planByName = planReorder(historicalOrderItems, currentMenu, {
      confirmedSubstitutions: ['truffle burrata'],
    });
    expect(planByName.availableItems).toHaveLength(1);
    expect(planByName.availableItems[0].id).toBe('dish-1');

    // 3. Explicit allowNameFallback flag
    const planByFlag = planReorder(historicalOrderItems, currentMenu, {
      allowNameFallback: true,
    });
    expect(planByFlag.availableItems).toHaveLength(1);
    expect(planByFlag.availableItems[0].id).toBe('dish-1');
  });

  it('merges repeated items from historical order into a single cart entry with combined quantity', () => {
    const historicalOrderItems = [
      {
        menuItemId: 'dish-1',
        name: 'Truffle Burrata',
        quantity: 1,
        unitPrice: 22.0,
      },
      {
        menuItemId: 'dish-1',
        name: 'Truffle Burrata',
        quantity: 2,
        unitPrice: 22.0,
      },
    ];

    const plan = planReorder(historicalOrderItems, currentMenu);

    expect(plan.availableItems).toHaveLength(1);
    expect(plan.availableItems[0].id).toBe('dish-1');
    expect(plan.availableItems[0].quantity).toBe(3);
  });

  it('handles empty input or empty menu safely without throwing', () => {
    expect(planReorder([], currentMenu)).toEqual({
      availableItems: [],
      unavailableCount: 0,
      priceChangedCount: 0,
      notices: [],
    });
    expect(planReorder([{ menuItemId: 'dish-1', name: 'Dish', quantity: 1 }], [])).toEqual({
      availableItems: [],
      unavailableCount: 1,
      priceChangedCount: 0,
      notices: ['"Dish" is no longer on the menu.'],
    });
  });

  describe('Reorder notice persistence and consumption', () => {
    const storage: Record<string, string> = {};

    afterEach(() => {
      for (const key of Object.keys(storage)) delete storage[key];
      vi.restoreAllMocks();
    });

    it('stores and consumes reorder notices via sessionStorage', () => {
      vi.stubGlobal('sessionStorage', {
        getItem: (key: string) => storage[key] ?? null,
        setItem: (key: string, val: string) => { storage[key] = val; },
        removeItem: (key: string) => { delete storage[key]; },
      });

      storeReorderNotice('Prices have been updated to the current menu.');
      expect(consumeReorderNotice()).toBe('Prices have been updated to the current menu.');
      // After consumption, notice is removed
      expect(consumeReorderNotice()).toBeNull();
    });
  });
});
