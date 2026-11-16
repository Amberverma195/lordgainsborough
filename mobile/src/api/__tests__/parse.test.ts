import {
  parseLoginResponse,
  parseMenuList,
  parseOrderCreation,
  parseRecoveryChallenge,
  parseStoreSettings,
} from '../parse';

const dish = {
  id: 'dish-1',
  name: 'Heritage Pie',
  description: 'Seasonal',
  price: 24,
  category: 'Mains',
  image: '/uploads/menu/pie.jpg',
  available: true,
  mostLoved: true,
};

describe('API response parsing', () => {
  it('accepts a menu list and drops a malformed row', () => {
    const parsed = parseMenuList([dish, { name: 'Missing id' }]);
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.value).toHaveLength(1);
      expect(parsed.value[0].mostLoved).toBe(true);
    }
  });

  it('rejects a menu payload that is not a list', () => {
    expect(parseMenuList({ items: [dish] }).ok).toBe(false);
  });

  it('rejects a menu list whose rows are all unusable', () => {
    expect(parseMenuList([{ price: '24' }]).ok).toBe(false);
  });

  it('requires the public store settings used for tax and pickup', () => {
    const parsed = parseStoreSettings({
      id: 'default',
      taxRate: 0.13,
      defaultReadyMinutes: 25,
      storeName: 'Lord Gainsborough',
      contactPhone: '',
      contactEmail: '',
      pickupOrdersEnabled: false,
    });
    expect(parsed.ok && parsed.value.pickupOrdersEnabled).toBe(false);
    expect(parseStoreSettings({ taxRate: 0.13 }).ok).toBe(false);
  });

  it('reads a successful login and its account role', () => {
    const parsed = parseLoginResponse({
      code: 'LOGIN_SUCCESS',
      token: 'jwt-token',
      name: 'Ada Member',
      email: 'ada@example.com',
      phone: '+1 (519)-555-0100',
      memberSince: 'November 2026',
      role: 'MEMBER',
      canUpdateProfile: true,
      noShowCount: 0,
      pickupDisabled: false,
    });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.user.role).toBe('MEMBER');
      expect(parsed.user.token).toBe('jwt-token');
    }
  });

  it('keeps an invalid login as a failure with its server code', () => {
    const parsed = parseLoginResponse({
      code: 'LOGIN_INVALID_CREDENTIALS',
      message: 'Invalid email, phone number, or password.',
    });
    expect(parsed).toMatchObject({ ok: false, code: 'LOGIN_INVALID_CREDENTIALS' });
  });

  it('does not treat a creation response without an order id as confirmed', () => {
    expect(parseOrderCreation({ order: { status: 'NEW' }, trackingToken: 'track' }).ok).toBe(false);
  });

  it('reads the confirmed order and server totals', () => {
    const parsed = parseOrderCreation({
      order: {
        id: 'order-123456',
        pickupDate: '2026-11-16',
        pickupTime: 'asap',
        status: 'NEW',
        items: [{ menuItemId: 'dish-1', name: 'Heritage Pie', quantity: 2, unitPrice: 24, total: 48 }],
        subtotal: 48,
        tax: 6.24,
        total: 54.24,
      },
      trackingToken: 'tracking-token',
    });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.value.order.total).toBe(54.24);
      expect(parsed.value.trackingToken).toBe('tracking-token');
    }
  });

  it('keeps signup challenge timing without assuming delivery succeeded', () => {
    const parsed = parseRecoveryChallenge({
      code: 'OTP_CAPTCHA_REQUIRED',
      message: 'Additional verification is required before continuing.',
      challengeId: 'challenge-1',
    });
    expect(parsed.ok && parsed.value.captchaRequired).toBe(true);
  });
});
