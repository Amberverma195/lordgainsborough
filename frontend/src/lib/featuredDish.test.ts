import { describe, it, expect, vi } from 'vitest';
import { filterAvailableDishes, fetchFeaturedDishes, MenuItem } from './featuredDish';

describe('featuredDish logic', () => {
  describe('filterAvailableDishes', () => {
    it('returns empty array for invalid or non-array inputs', () => {
      expect(filterAvailableDishes(null)).toEqual([]);
      expect(filterAvailableDishes(undefined)).toEqual([]);
      expect(filterAvailableDishes('not-an-array')).toEqual([]);
      expect(filterAvailableDishes(12345)).toEqual([]);
      expect(filterAvailableDishes({})).toEqual([]);
    });

    it('filters out unavailable items (available === false)', () => {
      const sample = [
        { id: '1', name: 'Fish & Chips', price: 19.99, available: true },
        { id: '2', name: 'Sold Out Ribeye', price: 125, available: false },
        { id: '3', name: 'Classic Melt', price: 20.99 }, // defaults to available
      ];

      const result = filterAvailableDishes(sample);
      expect(result).toHaveLength(2);
      expect(result.map((r) => r.name)).toEqual(['Fish & Chips', 'Classic Melt']);
    });

    it('filters out invalid or incomplete item objects', () => {
      const sample = [
        null,
        'string-entry',
        { id: '', name: 'No ID Dish', price: 10 },
        { id: '1', name: '', price: 10 },
        { id: '2', name: 'NaN Price', price: NaN },
        { id: '3', name: 'Negative Price', price: -5 },
        { id: '4', name: 'Valid Dish', price: 15.5 },
      ];

      const result = filterAvailableDishes(sample);
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('4');
      expect(result[0].name).toBe('Valid Dish');
      expect(result[0].price).toBe(15.5);
    });
  });

  describe('fetchFeaturedDishes', () => {
    it('returns available items when /menu/most-loved succeeds with available dishes', async () => {
      const mockFetch = vi.fn().mockImplementation((url: string) => {
        if (url.includes('/menu/most-loved')) {
          return Promise.resolve({
            ok: true,
            json: async () => [
              {
                id: 'fish-chips',
                name: 'Homemade Fish & Chips',
                description: 'Fresh cod in golden crispy batter',
                price: 19.99,
                available: true,
                mostLoved: true,
              },
            ],
          });
        }
        return Promise.reject(new Error('Unexpected call'));
      });

      const dishes = await fetchFeaturedDishes(mockFetch as unknown as typeof fetch);
      expect(dishes).toHaveLength(1);
      expect(dishes[0].name).toBe('Homemade Fish & Chips');
      expect(dishes[0].price).toBe(19.99);
      expect(mockFetch).toHaveBeenCalledTimes(1);
    });

    it('falls back to /menu when /menu/most-loved returns empty array', async () => {
      const mockFetch = vi.fn().mockImplementation((url: string) => {
        if (url.includes('/menu/most-loved')) {
          return Promise.resolve({
            ok: true,
            json: async () => [],
          });
        }
        if (url.includes('/menu')) {
          return Promise.resolve({
            ok: true,
            json: async () => [
              {
                id: 'clubhouse',
                name: 'Triple Decker Clubhouse',
                description: 'Classic toasted clubhouse',
                price: 18.99,
                available: true,
              },
            ],
          });
        }
        return Promise.reject(new Error('Not found'));
      });

      const dishes = await fetchFeaturedDishes(mockFetch as unknown as typeof fetch);
      expect(dishes).toHaveLength(1);
      expect(dishes[0].name).toBe('Triple Decker Clubhouse');
      expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it('falls back to /menu when /menu/most-loved returns non-ok status (404/500)', async () => {
      const mockFetch = vi.fn().mockImplementation((url: string) => {
        if (url.includes('/menu/most-loved')) {
          return Promise.resolve({
            ok: false,
            status: 404,
          });
        }
        if (url.includes('/menu')) {
          return Promise.resolve({
            ok: true,
            json: async () => [
              {
                id: 'melt',
                name: 'Classic Melt',
                price: 20.99,
                available: true,
              },
            ],
          });
        }
        return Promise.reject(new Error('Not found'));
      });

      const dishes = await fetchFeaturedDishes(mockFetch as unknown as typeof fetch);
      expect(dishes).toHaveLength(1);
      expect(dishes[0].name).toBe('Classic Melt');
    });

    it('returns empty array when network requests fail without showing fictional wagyu ribeye', async () => {
      const mockFetch = vi.fn().mockRejectedValue(new Error('Network error'));

      const dishes = await fetchFeaturedDishes(mockFetch as unknown as typeof fetch);
      expect(dishes).toEqual([]);
      // Confirmed: never returns fictional Wagyu Ribeye at $125
      expect(dishes.some((d: MenuItem) => d.name.toLowerCase().includes('wagyu'))).toBe(false);
    });

    it('returns empty array when all returned dishes are marked unavailable', async () => {
      const mockFetch = vi.fn().mockImplementation((url: string) => {
        if (url.includes('/menu/most-loved')) {
          return Promise.resolve({
            ok: true,
            json: async () => [
              { id: '1', name: 'Sold Out Wagyu', price: 125, available: false },
            ],
          });
        }
        if (url.includes('/menu')) {
          return Promise.resolve({
            ok: true,
            json: async () => [
              { id: '2', name: 'Sold Out Burger', price: 15, available: false },
            ],
          });
        }
        return Promise.reject(new Error('Not found'));
      });

      const dishes = await fetchFeaturedDishes(mockFetch as unknown as typeof fetch);
      expect(dishes).toEqual([]);
    });
  });
});
