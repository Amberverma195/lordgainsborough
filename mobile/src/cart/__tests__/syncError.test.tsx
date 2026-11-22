import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, render } from '@testing-library/react-native';
import React from 'react';
import { Pressable, Text, View } from 'react-native';

import { ApiError } from '../../api/errors';
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

function CartSyncErrorProbe() {
  const { ready, lines, notice, syncError, addItem, retrySync } = useCart();
  return (
    <View testID="cart-sync-error-probe">
      <Text testID="ready">{String(ready)}</Text>
      <Text testID="count">{String(lines.length)}</Text>
      <Text testID="notice">{notice ?? ''}</Text>
      <Text testID="sync-error">{syncError ?? ''}</Text>
      <Pressable
        testID="add-item-1"
        onPress={() => {
          addItem({
            id: 'item-sync-1',
            name: 'Wagyu Steak',
            price: 55,
            category: 'Main',
            available: true,
          } as any);
        }}
      >
        <Text>Add Item</Text>
      </Pressable>
      <Pressable
        testID="add-item-2"
        onPress={() => {
          addItem({
            id: 'item-sync-2',
            name: 'Red Wine',
            price: 18,
            category: 'Drinks',
            available: true,
          } as any);
        }}
      >
        <Text>Add Item 2</Text>
      </Pressable>
      <Pressable
        testID="manual-retry"
        onPress={() => {
          retrySync?.();
        }}
      >
        <Text>Manual Retry</Text>
      </Pressable>
    </View>
  );
}

describe('Cart sync error handling and controlled retry', () => {
  const testAccount = 'customer-sync@example.com';
  const pendingKey = getCartPendingStorageKey(testAccount);

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();

    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'token-sync-test',
        name: 'Sync Customer',
        email: testAccount,
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

  afterEach(() => {
    jest.useRealTimers();
  });

  it('informs the customer and schedules controlled retry with backoff when server save fails', async () => {
    mockSaveServerCart
      .mockRejectedValueOnce(new Error('Network disconnected'))
      .mockResolvedValueOnce(undefined);

    jest.useFakeTimers();

    const { getByTestId } = await render(
      <CartProvider>
        <CartSyncErrorProbe />
      </CartProvider>
    );

    // Initial load
    await act(async () => {
      jest.advanceTimersByTime(50);
    });
    expect(getByTestId('ready').props.children).toBe('true');

    // Add item to cart
    await act(async () => {
      fireEvent.press(getByTestId('add-item-1'));
    });

    expect(await AsyncStorage.getItem(pendingKey)).toBe('1');

    // Debounce fires after 800ms
    await act(async () => {
      jest.advanceTimersByTime(850);
    });

    // First save fired and failed
    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);
    expect(getByTestId('notice').props.children).toBe(
      'Unable to sync cart with your account. Retrying...'
    );
    expect(await AsyncStorage.getItem(pendingKey)).toBe('1');

    // Advance by 1st retry backoff (1000ms)
    await act(async () => {
      jest.advanceTimersByTime(1050);
    });

    // Second save fired and succeeded
    expect(mockSaveServerCart).toHaveBeenCalledTimes(2);
    expect(getByTestId('notice').props.children).toBe('');
    expect(await AsyncStorage.getItem(pendingKey)).toBeNull();
  });

  it('exhausts retries and informs customer changes are saved locally when server consistently fails', async () => {
    mockSaveServerCart.mockRejectedValue(new Error('500 Internal Server Error'));

    jest.useFakeTimers();

    const { getByTestId } = await render(
      <CartProvider>
        <CartSyncErrorProbe />
      </CartProvider>
    );

    await act(async () => {
      jest.advanceTimersByTime(50);
    });

    await act(async () => {
      fireEvent.press(getByTestId('add-item-1'));
    });

    // 1st attempt: 850ms (initial debounce)
    await act(async () => {
      jest.advanceTimersByTime(850);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);
    expect(getByTestId('notice').props.children).toBe(
      'Unable to sync cart with your account. Retrying...'
    );

    // 2nd attempt: 1st retry after 1000ms
    await act(async () => {
      jest.advanceTimersByTime(1050);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(2);

    // 3rd attempt: 2nd retry after 2000ms
    await act(async () => {
      jest.advanceTimersByTime(2050);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(3);

    // 4th attempt: 3rd retry after 4000ms
    await act(async () => {
      jest.advanceTimersByTime(4050);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(4);

    // Retries now exhausted (MAX_SYNC_RETRIES = 3 reached)
    expect(getByTestId('notice').props.children).toBe(
      'Cart saved locally, but online sync failed. Check your connection.'
    );

    // Advance another 10s: no further calls
    await act(async () => {
      jest.advanceTimersByTime(10000);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(4);

    // Local changes remain preserved in storage
    expect(await AsyncStorage.getItem(pendingKey)).toBe('1');
  });

  it('cancels pending retry when user modifies cart while waiting to retry', async () => {
    mockSaveServerCart
      .mockRejectedValueOnce(new Error('Transient timeout'))
      .mockResolvedValueOnce(undefined);

    jest.useFakeTimers();

    const { getByTestId } = await render(
      <CartProvider>
        <CartSyncErrorProbe />
      </CartProvider>
    );

    await act(async () => {
      jest.advanceTimersByTime(50);
    });

    await act(async () => {
      fireEvent.press(getByTestId('add-item-1'));
    });

    // 1st attempt fails
    await act(async () => {
      jest.advanceTimersByTime(850);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);
    expect(getByTestId('notice').props.children).toBe(
      'Unable to sync cart with your account. Retrying...'
    );

    // User modifies cart before 1000ms retry fires (at 300ms)
    await act(async () => {
      jest.advanceTimersByTime(300);
    });
    await act(async () => {
      fireEvent.press(getByTestId('add-item-2'));
    });

    // Notice is cleared on user mutation
    expect(getByTestId('notice').props.children).toBe('');

    // Advance past new debounce (850ms)
    await act(async () => {
      jest.advanceTimersByTime(850);
    });

    // New save fired with both items and succeeded
    expect(mockSaveServerCart).toHaveBeenCalledTimes(2);
    expect(await AsyncStorage.getItem(pendingKey)).toBeNull();
  });

  it('does not retry when server returns 401 unauthorized / session expired', async () => {
    mockSaveServerCart.mockRejectedValueOnce(
      new ApiError({
        status: 401,
        code: 'UNAUTHORIZED',
        message: 'Invalid or expired token',
      })
    );

    jest.useFakeTimers();

    const { getByTestId } = await render(
      <CartProvider>
        <CartSyncErrorProbe />
      </CartProvider>
    );

    await act(async () => {
      jest.advanceTimersByTime(50);
    });

    await act(async () => {
      fireEvent.press(getByTestId('add-item-1'));
    });

    await act(async () => {
      jest.advanceTimersByTime(850);
    });

    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);
    expect(getByTestId('notice').props.children).toBe(
      'Your session has expired. Sign in to sync your cart.'
    );

    // Advance time by 10s: no retries should be made for 401
    await act(async () => {
      jest.advanceTimersByTime(10000);
    });
    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);
  });

  it('allows manual retrySync to trigger an immediate save after failure', async () => {
    mockSaveServerCart
      .mockRejectedValueOnce(new Error('First failure'))
      .mockResolvedValueOnce(undefined);

    jest.useFakeTimers();

    const { getByTestId } = await render(
      <CartProvider>
        <CartSyncErrorProbe />
      </CartProvider>
    );

    await act(async () => {
      jest.advanceTimersByTime(50);
    });

    await act(async () => {
      fireEvent.press(getByTestId('add-item-1'));
    });

    await act(async () => {
      jest.advanceTimersByTime(850);
    });

    expect(mockSaveServerCart).toHaveBeenCalledTimes(1);

    // Trigger manual retry
    await act(async () => {
      fireEvent.press(getByTestId('manual-retry'));
    });

    expect(mockSaveServerCart).toHaveBeenCalledTimes(2);
    expect(getByTestId('notice').props.children).toBe('');
    expect(await AsyncStorage.getItem(pendingKey)).toBeNull();
  });
});
