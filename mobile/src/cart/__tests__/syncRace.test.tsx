import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, render } from '@testing-library/react-native';
import React from 'react';
import { Pressable, Text, View } from 'react-native';

import * as ordersApi from '../../api/orders';
import * as authContext from '../../auth/AuthContext';
import { CartProvider, useCart } from '../CartContext';
import { getCartPendingStorageKey } from '../storage';

jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

jest.mock('../../api/orders', () => ({
  fetchServerCart: jest.fn(),
  saveServerCart: jest.fn(),
}));

const mockFetchServerCart = ordersApi.fetchServerCart as jest.Mock;
const mockSaveServerCart = ordersApi.saveServerCart as jest.Mock;

function CartTestController() {
  const { ready, lines, addItem, updateQuantity } = useCart();
  return (
    <View testID="cart-controller">
      <Text testID="ready">{String(ready)}</Text>
      <Text testID="count">{String(lines.length)}</Text>
      <Pressable
        testID="add-item-1"
        onPress={() => {
          addItem({
            id: 'item-1',
            name: 'Dish 1',
            price: 10,
            category: 'Main',
            available: true,
          } as any);
        }}
      >
        <Text>Add Item 1</Text>
      </Pressable>
      <Pressable
        testID="inc-item-1"
        onPress={() => {
          updateQuantity('item-1', 2);
        }}
      >
        <Text>Inc Item 1</Text>
      </Pressable>
    </View>
  );
}

describe('Cart sync race conditions and pending marker protection', () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();

    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'token-race',
        name: 'Race User',
        email: 'race@example.com',
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
      establishSession: jest.fn(),
      updateUser: jest.fn(),
      finishAccountDeletion: jest.fn(),
    });

    mockFetchServerCart.mockResolvedValue([]);
  });

  it('serializes overlapping saves and preserves pending marker if mutated during in-flight save', async () => {
    let resolveFirstSave!: () => void;
    let resolveSecondSave!: () => void;

    mockSaveServerCart
      .mockImplementationOnce(
        () =>
          new Promise<void>((res) => {
            resolveFirstSave = res;
          })
      )
      .mockImplementationOnce(
        () =>
          new Promise<void>((res) => {
            resolveSecondSave = res;
          })
      );

    jest.useFakeTimers();

    const { getByTestId } = await render(
      <CartProvider>
        <CartTestController />
      </CartProvider>
    );

    // Advance past initial load
    await act(async () => {
      jest.advanceTimersByTime(50);
    });
    expect(getByTestId('ready').props.children).toBe('true');

    // 1. Add item 1
    await act(async () => {
      fireEvent.press(getByTestId('add-item-1'));
    });

    // Check pending marker is set
    const pendingKey = getCartPendingStorageKey('race@example.com');
    const pendingVal1 = await AsyncStorage.getItem(pendingKey);
    expect(pendingVal1).toBe('1');

    // Advance debounce timer (800ms) to trigger first save
    await act(async () => {
      jest.advanceTimersByTime(850);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);

    // 2. While first save is still in flight, mutate cart again
    await act(async () => {
      fireEvent.press(getByTestId('inc-item-1'));
    });

    // Advance debounce timer again
    await act(async () => {
      jest.advanceTimersByTime(850);
    });

    // Second network save should NOT have been fired yet because first is still in flight
    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);

    // 3. Resolve first save
    await act(async () => {
      resolveFirstSave();
    });

    // The pending marker should STILL exist because a mutation occurred while first save was running
    const pendingAfterFirst = await AsyncStorage.getItem(pendingKey);
    expect(pendingAfterFirst).toBe('1');

    // Resolving first save should immediately trigger queued save
    expect(mockSaveServerCart).toHaveBeenCalledTimes(2);

    // 4. Resolve second save
    await act(async () => {
      resolveSecondSave();
    });

    // Now that second save finished with no intervening mutations, pending marker is cleared
    const pendingAfterSecond = await AsyncStorage.getItem(pendingKey);
    expect(pendingAfterSecond).toBeNull();

    jest.useRealTimers();
  });
});
