import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react-native';
import React, { useEffect } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const mockFocusEffect = jest.fn((cb: () => void) => {
  useEffect(() => {
    cb();
  }, []);
});

jest.mock('expo-router', () => ({
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
  },
  useLocalSearchParams: () => ({ id: 'ord-test-123' }),
  useFocusEffect: (cb: () => void) => mockFocusEffect(cb),
}));

jest.mock('../../order/useOrderRealtime', () => ({
  useOrderRealtime: jest.fn(),
}));

jest.mock('../../auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('../../api/orders', () => ({
  fetchOrder: jest.fn(),
  fetchOrderHistory: jest.fn(),
}));

import * as ordersApi from '../../api/orders';
import { CustomerOrderView } from '../../api/parse';
import { useAuth } from '../../auth/AuthContext';
import { useOrderRealtime } from '../useOrderRealtime';
import OrderDetailScreen from '../../app/order/[id]';
import OrdersScreen from '../../app/(tabs)/orders';

const mockUseAuth = useAuth as jest.Mock;
const mockFetchOrder = ordersApi.fetchOrder as jest.Mock;
const mockFetchOrderHistory = ordersApi.fetchOrderHistory as jest.Mock;
const mockUseOrderRealtime = useOrderRealtime as jest.Mock;

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

describe('Order refresh, focus, and realtime updates', () => {
  let queryClient: QueryClient;

  const activeOrder: CustomerOrderView = {
    id: 'ord-test-123',
    pickupDate: '2026-11-20',
    pickupTime: '17:30',
    specialInstructions: 'Extra napkins',
    status: 'ACCEPTED',
    averageReadyMinutes: 15,
    items: [
      {
        menuItemId: 'fish-chips',
        name: 'Fish & Chips',
        quantity: 1,
        unitPrice: 16.5,
        total: 16.5,
        notes: '',
      },
    ],
    subtotal: 16.5,
    tax: 2.15,
    total: 18.65,
    createdAt: '2026-11-20T17:00:00Z',
    reviewed: false,
    customerName: 'Jane Doe',
    customerEmail: 'jane@example.com',
    customerPhone: '5195550100',
  };

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();

    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    });

    mockUseAuth.mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'user-jwt',
        name: 'Jane Doe',
        email: 'jane@example.com',
      },
      refresh: jest.fn(),
    });
  });

  afterEach(() => {
    queryClient.clear();
  });

  it('OrderDetailScreen registers focus effect and connects realtime hook', async () => {
    mockFetchOrder.mockResolvedValue(activeOrder);

    const utils = await render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <QueryClientProvider client={queryClient}>
          <OrderDetailScreen />
        </QueryClientProvider>
      </SafeAreaProvider>,
    );

    await waitFor(() => {
      expect(mockFetchOrder).toHaveBeenCalledWith('ord-test-123', 'user-jwt', null);
    });

    expect(await utils.findByText('In progress')).toBeTruthy();
    expect(await utils.findByText('Restaurant: (519) 472-4168')).toBeTruthy();
    expect(mockFocusEffect).toHaveBeenCalled();
    expect(mockUseOrderRealtime).toHaveBeenCalledWith(
      expect.objectContaining({
        orderId: 'ord-test-123',
        token: 'user-jwt',
        enabled: true,
      }),
    );
  });

  it('OrderDetailScreen updates immediately when socket onOrderUpdated is invoked', async () => {
    mockFetchOrder.mockResolvedValue(activeOrder);

    const utils = await render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <QueryClientProvider client={queryClient}>
          <OrderDetailScreen />
        </QueryClientProvider>
      </SafeAreaProvider>,
    );

    expect(await utils.findByText('In progress')).toBeTruthy();

    // Trigger realtime update callback inside act
    const realtimeOptions = mockUseOrderRealtime.mock.calls[0][0];
    await act(async () => {
      realtimeOptions.onOrderUpdated({ ...activeOrder, status: 'READY' });
    });

    expect(await utils.findByText('Ready for pickup')).toBeTruthy();
  });

  it('OrdersScreen registers focus effect and displays active orders', async () => {
    mockFetchOrderHistory.mockResolvedValue([activeOrder]);

    const utils = await render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <QueryClientProvider client={queryClient}>
          <OrdersScreen />
        </QueryClientProvider>
      </SafeAreaProvider>,
    );

    await waitFor(() => {
      expect(mockFetchOrderHistory).toHaveBeenCalledWith('user-jwt');
    });

    expect(await utils.findByText('In progress')).toBeTruthy();
    expect(mockFocusEffect).toHaveBeenCalled();
  });
});
