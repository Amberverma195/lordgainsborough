import { MenuItem } from '../../api/parse';
import { addToCart, mergeCarts, mergeGuestCart, reconcileWithCatalog, removeFromCart, setQuantity } from '../operations';
import { CartLine } from '../types';

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
});
