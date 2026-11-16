import { CartLine } from '../../cart/types';
import { validateCheckout } from '../validate';

const line: CartLine = {
  menuItemId: 'pie',
  name: 'Heritage Pie',
  description: '',
  unitPrice: 24,
  category: 'Mains',
  image: '',
  quantity: 1,
  available: true,
};

const referenceDate = new Date('2026-11-15T17:00:00Z');

function draft(overrides: Partial<Parameters<typeof validateCheckout>[0]> = {}) {
  return {
    signedIn: true,
    pickupDisabled: false,
    pickupOrdersEnabled: true,
    name: 'Ada Member',
    email: 'ada@example.com',
    phone: '+1 (519)-555-0100',
    lines: [line],
    pickupDate: '2026-11-16',
    pickupTime: '6:00 PM',
    specialInstructions: '',
    taxRate: 0.13,
    referenceDate,
    ...overrides,
  };
}

describe('checkout validation', () => {
  it('accepts a signed-in pickup inside restaurant hours', () => {
    expect(validateCheckout(draft())).toBeNull();
  });

  it('stops checkout when pickup is paused, the account is blocked, or the cart is empty', () => {
    expect(validateCheckout(draft({ pickupOrdersEnabled: false }))?.code).toBe('PICKUP_ORDERS_PAUSED');
    expect(validateCheckout(draft({ pickupDisabled: true }))?.code).toBe('PICKUP_DISABLED_NO_SHOW');
    expect(validateCheckout(draft({ lines: [] }))?.code).toBe('EMPTY_CART');
    expect(validateCheckout(draft({ signedIn: false }))?.code).toBe('AUTHENTICATION_REQUIRED');
  });

  it('requires a ten-digit account phone and a future pickup time', () => {
    expect(validateCheckout(draft({ phone: '555' }))?.code).toBe('MISSING_CUSTOMER_DETAILS');
    expect(validateCheckout(draft({ pickupDate: '2026-11-15', pickupTime: '11:00 AM' }))?.code).toBe('PAST_PICKUP_TIME');
  });

  it('blocks an unavailable line before it can be submitted', () => {
    expect(validateCheckout(draft({ lines: [{ ...line, available: false }] }))?.code).toBe('CART_ITEM_UNAVAILABLE');
  });
});
