import { describe, it, expect } from 'vitest';
import { sortMenuItems, SortableMenuItem, SortableCategory } from './menuSort';

describe('Admin Menu Management - Stability & Interaction', () => {
  const categories: SortableCategory[] = [
    { id: 'cat-1', name: 'Appetizers', displayOrder: 1 },
    { id: 'cat-2', name: 'Pizzas', displayOrder: 2 },
    { id: 'cat-3', name: 'Salads', displayOrder: 3 },
  ];

  const initialItems: SortableMenuItem[] = [
    { id: '1', name: 'Cheesy Garlic Bread', category: 'Appetizers', price: 13.99, available: true },
    { id: '2', name: 'Mozzarella Sticks', category: 'Appetizers', price: 14.99, available: true },
    { id: '3', name: 'Pickle Spears', category: 'Appetizers', price: 11.99, available: true },
    { id: '4', name: 'Cheese Pizza (10")', category: 'Pizzas', price: 17.00, available: true },
    { id: '5', name: 'House Salad (Small)', category: 'Salads', price: 10.99, available: true },
  ];

  it('preserves exact index order when toggling availability of first, middle, or last item', () => {
    let list = sortMenuItems(initialItems, categories);
    const originalIds = list.map(i => i.id);

    // 1. Toggle first item
    list = list.map(item => (item.id === '1' ? { ...item, available: !item.available } : item));
    let sorted = sortMenuItems(list, categories);
    expect(sorted.map(i => i.id)).toEqual(originalIds);
    expect(sorted[0].available).toBe(false);

    // 2. Toggle middle item ('3' Pickle Spears)
    list = list.map(item => (item.id === '3' ? { ...item, available: !item.available } : item));
    sorted = sortMenuItems(list, categories);
    expect(sorted.map(i => i.id)).toEqual(originalIds);
    expect(sorted[2].available).toBe(false);

    // 3. Toggle last item ('5' House Salad)
    list = list.map(item => (item.id === '5' ? { ...item, available: !item.available } : item));
    sorted = sortMenuItems(list, categories);
    expect(sorted.map(i => i.id)).toEqual(originalIds);
    expect(sorted[4].available).toBe(false);

    // 4. Toggle all back to available
    list = list.map(item => ({ ...item, available: true }));
    sorted = sortMenuItems(list, categories);
    expect(sorted.map(i => i.id)).toEqual(originalIds);
  });

  it('updates an item in-place without altering other items when price or description is edited', () => {
    const list = sortMenuItems(initialItems, categories);
    const updated = list.map(item =>
      item.id === '2' ? { ...item, price: 15.99, description: 'Updated recipe' } : item
    );

    const sorted = sortMenuItems(updated, categories);
    expect(sorted.map(i => i.id)).toEqual(list.map(i => i.id));
    expect(sorted[1].price).toBe(15.99);
    expect(sorted[1].description).toBe('Updated recipe');
  });

  it('correctly tracks dirty form state against initial values', () => {
    const initialForm = {
      name: 'Mozzarella Sticks',
      description: 'Six breaded mozzarella sticks',
      price: '14.99',
      category: 'Appetizers',
      image: '/menu/sticks.jpg',
      available: true,
    };

    const isDirty = (form: typeof initialForm) =>
      form.name !== initialForm.name ||
      form.description !== initialForm.description ||
      form.price !== initialForm.price ||
      form.category !== initialForm.category ||
      form.available !== initialForm.available ||
      form.image !== initialForm.image;

    // Initially clean
    expect(isDirty({ ...initialForm })).toBe(false);

    // Name edited
    expect(isDirty({ ...initialForm, name: 'Mozzarella Sticks (Large)' })).toBe(true);

    // Price edited
    expect(isDirty({ ...initialForm, price: '16.99' })).toBe(true);

    // Availability edited
    expect(isDirty({ ...initialForm, available: false })).toBe(true);

    // Image edited
    expect(isDirty({ ...initialForm, image: '/menu/new.jpg' })).toBe(true);
  });

  it('validates required fields before allowing submission', () => {
    const validate = (form: { name: string; price: string; category: string; description: string }) => {
      if (!form.name.trim()) return 'Please enter a dish name.';
      const p = parseFloat(form.price);
      if (isNaN(p) || p < 0) return 'Please enter a valid non-negative price.';
      if (!form.category) return 'Please select a category.';
      if (!form.description.trim()) return 'Please enter a description.';
      return null;
    };

    expect(validate({ name: '', price: '10', category: 'Appetizers', description: 'desc' })).toBe('Please enter a dish name.');
    expect(validate({ name: 'Dish', price: '-5', category: 'Appetizers', description: 'desc' })).toBe('Please enter a valid non-negative price.');
    expect(validate({ name: 'Dish', price: 'abc', category: 'Appetizers', description: 'desc' })).toBe('Please enter a valid non-negative price.');
    expect(validate({ name: 'Dish', price: '10', category: '', description: 'desc' })).toBe('Please select a category.');
    expect(validate({ name: 'Dish', price: '10', category: 'Appetizers', description: '   ' })).toBe('Please enter a description.');
    expect(validate({ name: 'Dish', price: '10.50', category: 'Appetizers', description: 'Good food' })).toBeNull();
  });
});
