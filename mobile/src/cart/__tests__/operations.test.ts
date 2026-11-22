import { MenuItem } from '../../api/parse';
import { addToCart, enforceCartLimits, mergeCarts, mergeGuestCart, reconcileWithCatalog, removeFromCart, setQuantity } from '../operations';
import { CartLine, MAX_CART_LINES, MAX_ITEM_QUANTITY, MAX_TOTAL_QUANTITY } from '../types';

const pie: MenuItem = {
  id: 'pie',
  name: 'Heritage Pie',
  description: 'Seasonal',
  price: 24,
  category: 'Mains',
  image: '',
  available: true,
  mostLoved: false,
};

describe('cart operations', () => {
  it('adds a dish and increases its quantity', () => {
    const first = addToCart([], pie, 1);
    const second = addToCart(first.cart, { ...pie, price: 26 }, 2);
    expect(second.error).toBeNull();
    expect(second.cart).toEqual([expect.objectContaining({ menuItemId: 'pie', quantity: 3, unitPrice: 26 })]);
  });

  it('refuses an unavailable dish and a quantity above the server limit', () => {
    expect(addToCart([], { ...pie, available: false }).error).toMatch(/unavailable/i);
    const full = addToCart([], pie, 20);
    expect(addToCart(full.cart, pie, 1).error).toMatch(/20/);
  });

  it('removes a line when the quantity drops to zero', () => {
    const added = addToCart([], pie, 2);
    expect(setQuantity(added.cart, 'pie', 0).cart).toEqual([]);
    expect(removeFromCart(added.cart, 'pie')).toEqual([]);
  });

  it('replaces client prices with the catalog and flags a missing dish', () => {
    const cart: CartLine[] = [{
      menuItemId: 'pie',
      name: 'Old name',
      description: '',
      unitPrice: 1,
      category: '',
      image: '',
      quantity: 2,
      available: true,
    }, {
      menuItemId: 'gone',
      name: 'Retired dish',
      description: '',
      unitPrice: 9,
      category: '',
      image: '',
      quantity: 1,
      available: true,
    }];
    const reconciled = reconcileWithCatalog(cart, [pie]);
    expect(reconciled[0]).toMatchObject({ name: 'Heritage Pie', unitPrice: 24, available: true });
    expect(reconciled[1].available).toBe(false);
  });

  it('mergeCarts preserves server quantities when server is authoritative, avoiding resurrection of reduced items', () => {
    const localCart: CartLine[] = [{
      menuItemId: 'pie',
      name: 'Heritage Pie',
      description: '',
      unitPrice: 24,
      category: 'Mains',
      image: '',
      quantity: 5,
      available: true,
    }];
    const remoteCart: CartLine[] = [{
      menuItemId: 'pie',
      name: 'Heritage Pie',
      description: '',
      unitPrice: 24,
      category: 'Mains',
      image: '',
      quantity: 1, // Reduced on website/desktop
      available: true,
    }];

    const merged = mergeCarts(localCart, remoteCart, { preserveServerQuantities: true });
    expect(merged).toHaveLength(1);
    expect(merged[0].quantity).toBe(1);
  });

  it('mergeGuestCart combines guest quantities into account cart and adds new items', () => {
    const guestCart: CartLine[] = [
      {
        menuItemId: 'pie',
        name: 'Heritage Pie',
        description: '',
        unitPrice: 24,
        category: 'Mains',
        image: '',
        quantity: 1,
        available: true,
      },
      {
        menuItemId: 'soup',
        name: 'Soup',
        description: '',
        unitPrice: 10,
        category: 'Starters',
        image: '',
        quantity: 2,
        available: true,
      },
    ];
    const accountCart: CartLine[] = [
      {
        menuItemId: 'pie',
        name: 'Heritage Pie',
        description: '',
        unitPrice: 24,
        category: 'Mains',
        image: '',
        quantity: 2,
        available: true,
      },
    ];

    const merged = mergeGuestCart(guestCart, accountCart);
    expect(merged).toHaveLength(2);
    expect(merged.find((i) => i.menuItemId === 'pie')?.quantity).toBe(3);
    expect(merged.find((i) => i.menuItemId === 'soup')?.quantity).toBe(2);
  });

  it('mergeGuestCart caps individual dish quantity at MAX_ITEM_QUANTITY (20)', () => {
    const guestCart: CartLine[] = [{
      menuItemId: 'pie',
      name: 'Heritage Pie',
      description: '',
      unitPrice: 24,
      category: 'Mains',
      image: '',
      quantity: 10,
      available: true,
    }];
    const accountCart: CartLine[] = [{
      menuItemId: 'pie',
      name: 'Heritage Pie',
      description: '',
      unitPrice: 24,
      category: 'Mains',
      image: '',
      quantity: 15,
      available: true,
    }];

    const merged = mergeGuestCart(guestCart, accountCart);
    expect(merged).toHaveLength(1);
    expect(merged[0].quantity).toBe(MAX_ITEM_QUANTITY);
  });

  it('mergeGuestCart enforces MAX_CART_LINES (30) when combining individually valid carts', () => {
    const makeLine = (id: string, qty = 1): CartLine => ({
      menuItemId: id,
      name: `Dish ${id}`,
      description: '',
      unitPrice: 10,
      category: 'Mains',
      image: '',
      quantity: qty,
      available: true,
    });

    // 20 distinct account dishes (valid <= 30)
    const accountCart: CartLine[] = Array.from({ length: 20 }, (_, i) => makeLine(`acc-${i + 1}`));
    // 15 distinct guest dishes (valid <= 30)
    const guestCart: CartLine[] = Array.from({ length: 15 }, (_, i) => makeLine(`guest-${i + 1}`));

    const merged = mergeGuestCart(guestCart, accountCart);
    expect(merged).toHaveLength(MAX_CART_LINES);
    // All 20 account dishes must be preserved
    for (let i = 1; i <= 20; i++) {
      expect(merged.some((line) => line.menuItemId === `acc-${i}`)).toBe(true);
    }
    // Only 10 guest dishes fit before hitting the 30-line ceiling
    const guestItemsInMerged = merged.filter((line) => line.menuItemId.startsWith('guest-'));
    expect(guestItemsInMerged).toHaveLength(10);
  });

  it('mergeGuestCart enforces MAX_TOTAL_QUANTITY (50) when combining individually valid carts', () => {
    const accountCart: CartLine[] = [
      {
        menuItemId: 'item-1',
        name: 'Item 1',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 20,
        available: true,
      },
      {
        menuItemId: 'item-2',
        name: 'Item 2',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 20,
        available: true,
      },
    ]; // Total = 40 (valid <= 50)

    const guestCart: CartLine[] = [
      {
        menuItemId: 'item-3',
        name: 'Item 3',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 15,
        available: true,
      },
      {
        menuItemId: 'item-4',
        name: 'Item 4',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 5,
        available: true,
      },
    ]; // Total = 20 (valid <= 50)

    const merged = mergeGuestCart(guestCart, accountCart);
    const totalQty = merged.reduce((sum, line) => sum + line.quantity, 0);
    expect(totalQty).toBe(MAX_TOTAL_QUANTITY);

    // Account lines preserved in full
    expect(merged.find((i) => i.menuItemId === 'item-1')?.quantity).toBe(20);
    expect(merged.find((i) => i.menuItemId === 'item-2')?.quantity).toBe(20);
    // Guest line gets remaining 10 units
    expect(merged.find((i) => i.menuItemId === 'item-3')?.quantity).toBe(10);
    // item-4 cannot be added because 50 is reached
    expect(merged.find((i) => i.menuItemId === 'item-4')).toBeUndefined();
  });

  it('mergeGuestCart protects account cart from quota exhaustion if account cart is already at MAX_TOTAL_QUANTITY', () => {
    const accountCart: CartLine[] = [
      {
        menuItemId: 'item-1',
        name: 'Item 1',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 20,
        available: true,
      },
      {
        menuItemId: 'item-2',
        name: 'Item 2',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 20,
        available: true,
      },
      {
        menuItemId: 'item-3',
        name: 'Item 3',
        description: '',
        unitPrice: 15,
        category: 'Mains',
        image: '',
        quantity: 10,
        available: true,
      },
    ]; // Total = 50

    const guestCart: CartLine[] = [
      {
        menuItemId: 'guest-dish',
        name: 'Guest Dish',
        description: '',
        unitPrice: 12,
        category: 'Starters',
        image: '',
        quantity: 5,
        available: true,
      },
    ];

    const merged = mergeGuestCart(guestCart, accountCart);
    expect(merged).toHaveLength(3);
    const totalQty = merged.reduce((sum, line) => sum + line.quantity, 0);
    expect(totalQty).toBe(50);
    expect(merged.find((i) => i.menuItemId === 'guest-dish')).toBeUndefined();
  });

  it('enforceCartLimits clamps oversized line count and total quantity', () => {
    const oversizedLines: CartLine[] = Array.from({ length: 40 }, (_, i) => ({
      menuItemId: `dish-${i}`,
      name: `Dish ${i}`,
      description: '',
      unitPrice: 10,
      category: 'Mains',
      image: '',
      quantity: 5,
      available: true,
    }));

    const result = enforceCartLimits(oversizedLines);
    expect(result.length).toBeLessThanOrEqual(MAX_CART_LINES);
    const total = result.reduce((sum, line) => sum + line.quantity, 0);
    expect(total).toBe(MAX_TOTAL_QUANTITY);
  });
});
