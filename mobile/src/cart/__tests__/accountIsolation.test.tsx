import AsyncStorage from '@react-native-async-storage/async-storage';
import { render, screen, waitFor } from '@testing-library/react-native';
import React from 'react';
import { Text, View } from 'react-native';

jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

import * as ordersApi from '../../api/orders';
import * as authContext from '../../auth/AuthContext';
import { CartProvider, useCart } from '../CartContext';
import { getCartStorageKey, getPickupStorageKey, LEGACY_CART_KEY, LEGACY_PICKUP_KEY } from '../storage';

jest.mock('../../api/orders', () => ({
  fetchServerCart: jest.fn(),
  saveServerCart: jest.fn().mockResolvedValue(undefined),
}));

const mockFetchServerCart = ordersApi.fetchServerCart as jest.Mock;

function CartProbe() {
  const { ready, lines } = useCart();
  return (
    <View testID="cart-probe">
      <Text testID="ready">{String(ready)}</Text>
      <Text testID="line-count">{String(lines.length)}</Text>
      {lines.map((l) => (
        <Text key={l.menuItemId} testID={`line-${l.menuItemId}`}>
          {l.name}
        </Text>
      ))}
    </View>
  );
}

describe('Cart account isolation', () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();
  });

  it('purges legacy unisolated keys on initialization', async () => {
    await AsyncStorage.setItem(LEGACY_CART_KEY, JSON.stringify([{ menuItemId: 'legacy' }]));
    await AsyncStorage.setItem(LEGACY_PICKUP_KEY, JSON.stringify({ date: '2026-11-20' }));

    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedOut',
      user: null,
      signIn: jest.fn(),
      signOut: jest.fn(),
      refresh: jest.fn(),
    });

    render(
      <CartProvider>
        <CartProbe />
      </CartProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('ready').props.children).toBe('true'));
    expect(await AsyncStorage.getItem(LEGACY_CART_KEY)).toBeNull();
    expect(await AsyncStorage.getItem(LEGACY_PICKUP_KEY)).toBeNull();
  });

  it('loads Customer A cart from account-scoped storage for Customer A', async () => {
    const keyA = getCartStorageKey('customera@example.com');
    await AsyncStorage.setItem(
      keyA,
      JSON.stringify([
        {
          menuItemId: 'd1',
          name: 'Roast Beef',
          unitPrice: 25.0,
          quantity: 2,
          available: true,
        },
      ]),
    );

    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'tok-A',
        name: 'Customer A',
        email: 'customera@example.com',
        phone: '',
        memberSince: '',
        role: 'CUSTOMER',
        canUpdateProfile: true,
        nextProfileUpdateAt: null,
        noShowCount: 0,
        pickupDisabled: false,
      },
      signIn: jest.fn(),
      signOut: jest.fn(),
      refresh: jest.fn(),
    });
    mockFetchServerCart.mockResolvedValueOnce([]);

    render(
      <CartProvider>
        <CartProbe />
      </CartProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('ready').props.children).toBe('true'));
    expect(screen.getByTestId('line-count').props.children).toBe('1');
    expect(screen.getByTestId('line-d1')).toBeTruthy();
  });

  it('shows empty guest cart when signed out even if Customer A had stored items', async () => {
    const keyA = getCartStorageKey('customera@example.com');
    await AsyncStorage.setItem(
      keyA,
      JSON.stringify([
        {
          menuItemId: 'd1',
          name: 'Roast Beef',
          unitPrice: 25.0,
          quantity: 2,
          available: true,
        },
      ]),
    );

    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedOut',
      user: null,
      signIn: jest.fn(),
      signOut: jest.fn(),
      refresh: jest.fn(),
    });

    render(
      <CartProvider>
        <CartProbe />
      </CartProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('ready').props.children).toBe('true'));
    expect(screen.getByTestId('line-count').props.children).toBe('0');
    expect(screen.queryByTestId('line-d1')).toBeNull();
  });

  it('ensures Customer B does not receive Customer A items and loads Customer B items', async () => {
    // Customer A has Roast Beef
    const keyA = getCartStorageKey('customera@example.com');
    await AsyncStorage.setItem(
      keyA,
      JSON.stringify([
        {
          menuItemId: 'd1',
          name: 'Roast Beef',
          unitPrice: 25.0,
          quantity: 2,
          available: true,
        },
      ]),
    );

    // Customer B signs in with Yorkshire Pudding from server
    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'tok-B',
        name: 'Customer B',
        email: 'customerb@example.com',
        phone: '',
        memberSince: '',
        role: 'CUSTOMER',
        canUpdateProfile: true,
        nextProfileUpdateAt: null,
        noShowCount: 0,
        pickupDisabled: false,
      },
      signIn: jest.fn(),
      signOut: jest.fn(),
      refresh: jest.fn(),
    });
    mockFetchServerCart.mockResolvedValueOnce([
      {
        menuItemId: 'd2',
        name: 'Yorkshire Pudding',
        description: '',
        price: 8.0,
        category: 'Sides',
        image: '',
        quantity: 1,
      },
    ]);

    render(
      <CartProvider>
        <CartProbe />
      </CartProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('ready').props.children).toBe('true'));
    expect(screen.getByTestId('line-count').props.children).toBe('1');
    expect(screen.getByTestId('line-d2')).toBeTruthy();
    expect(screen.queryByTestId('line-d1')).toBeNull();
  });
});
