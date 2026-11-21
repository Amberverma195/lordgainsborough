import { toAccountNamespace } from '../../auth/accountNamespace';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import { router } from 'expo-router';
import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo-crypto', () => ({
  randomUUID: () => '11111111-2222-3333-4444-555555555555',
}));

jest.mock('expo-router', () => ({
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
  },
}));

jest.mock('../../api/catalog', () => ({
  fetchStoreSettings: jest.fn(),
}));

jest.mock('../../api/orders', () => ({
  createOrder: jest.fn(),
}));

jest.mock('../../auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('../../cart/CartContext', () => ({
  useCart: jest.fn(),
}));

import * as catalogApi from '../../api/catalog';
import * as ordersApi from '../../api/orders';
import { ApiError } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { useCart } from '../../cart/CartContext';
import CheckoutScreen from '../../app/checkout';
import { UNCERTAIN_ORDER_MESSAGE } from '../validate';
import { pickupChoices, pickupDateChoices } from '../pickup';

const mockCreateOrder = ordersApi.createOrder as jest.Mock;
const mockFetchStoreSettings = catalogApi.fetchStoreSettings as jest.Mock;
const mockUseAuth = useAuth as jest.Mock;
const mockUseCart = useCart as jest.Mock;

describe('Checkout uncertain order submission recovery', () => {
  let queryClient: QueryClient;
  const mockSetPickup = jest.fn();
  const validDate = pickupDateChoices()[1] ?? pickupDateChoices()[0];
  const validTime = pickupChoices(validDate)[0];

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });

    mockFetchStoreSettings.mockResolvedValue({
      pickupOrdersEnabled: true,
      defaultReadyMinutes: 20,
      taxRate: 0.13,
    });

    mockUseAuth.mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'test-jwt',
        name: 'Jane Doe',
        email: 'jane@example.com',
        phone: '4165551234',
        memberSince: '2026-01-01',
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

    mockUseCart.mockReturnValue({
      ready: true,
      lines: [
        {
          menuItemId: 'fish-chips',
          name: 'Fish & Chips',
          unitPrice: 22.0,
          quantity: 1,
          available: true,
        },
      ],
      pickupDate: validDate,
      pickupTime: validTime,
      count: 1,
      total: 22.0,
      setPickup: mockSetPickup,
      add: jest.fn(),
      setQuantity: jest.fn(),
      remove: jest.fn(),
      clear: jest.fn(),
      estimate: () => ({
        subtotal: 22.0,
        tax: 2.86,
        total: 24.86,
        lineCount: 1,
        itemCount: 1,
      }),
    });
  });

  afterEach(async () => {
    await act(async () => {
      cleanup();
    });
    queryClient.clear();
  });

  const initialMetrics = {
    frame: { x: 0, y: 0, width: 390, height: 844 },
    insets: { top: 0, left: 0, right: 0, bottom: 0 },
  };

  const renderScreen = () =>
    render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <QueryClientProvider client={queryClient}>
          <CheckoutScreen />
        </QueryClientProvider>
      </SafeAreaProvider>,
    );

  it('displays recovery options on uncertain 500 server error and navigates to orders on request', async () => {
    mockCreateOrder.mockRejectedValueOnce(
      new ApiError({
        status: 500,
        code: 'INTERNAL_ERROR',
        message: 'Internal Server Error',
      }),
    );

    await renderScreen();

    await waitFor(() => {
      expect(screen.queryByText('Loading restaurant settings')).toBeNull();
      expect(screen.getByText('Place pickup order')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Place pickup order'));
    });

    await waitFor(() => {
      expect(screen.getByText('Submission Status Uncertain')).toBeTruthy();
      expect(screen.getByText(UNCERTAIN_ORDER_MESSAGE)).toBeTruthy();
      expect(screen.getByText('Check existing orders')).toBeTruthy();
      expect(screen.getByText("I've checked, try again")).toBeTruthy();
    });

    // The primary place order button is halted while in uncertain state
    expect(screen.queryByText('Place pickup order')).toBeNull();

    // Check existing orders navigates to (tabs)/orders
    await act(async () => {
      fireEvent.press(screen.getByText('Check existing orders'));
    });
    expect(router.push).toHaveBeenCalledWith('/(tabs)/orders');

    // Pressing "I've checked, try again" clears uncertain state and restores Place pickup order button
    await act(async () => {
      fireEvent.press(screen.getByText("I've checked, try again"));
    });

    await waitFor(() => {
      expect(screen.queryByText('Submission Status Uncertain')).toBeNull();
      expect(screen.getByText('Place pickup order')).toBeTruthy();
    });
  });

  it('displays recovery options on network drop (status 0)', async () => {
    mockCreateOrder.mockRejectedValueOnce(
      new ApiError({
        status: 0,
        code: 'NETWORK_ERROR',
        message: 'Network offline',
      }),
    );

    await renderScreen();

    await waitFor(() => {
      expect(screen.queryByText('Loading restaurant settings')).toBeNull();
      expect(screen.getByText('Place pickup order')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Place pickup order'));
    });

    await waitFor(() => {
      expect(screen.getByText('Submission Status Uncertain')).toBeTruthy();
    });
  });

  it('shows standard error without blocking recovery card on definitive client error (e.g. 400)', async () => {
    mockCreateOrder.mockRejectedValueOnce(
      new ApiError({
        status: 400,
        code: 'INVALID_PICKUP_TIME',
        message: 'Selected pickup time is outside operating hours',
      }),
    );

    await renderScreen();

    await waitFor(() => {
      expect(screen.queryByText('Loading restaurant settings')).toBeNull();
      expect(screen.getByText('Place pickup order')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Place pickup order'));
    });

    await waitFor(() => {
      expect(
        screen.getByText('Selected pickup time is outside operating hours'),
      ).toBeTruthy();
    });

    expect(screen.queryByText('Submission Status Uncertain')).toBeNull();
    expect(screen.queryByText('Check existing orders')).toBeNull();
    expect(screen.getByText('Place pickup order')).toBeTruthy();
  });

  it('keeps uncertain submission blocked when the user edits instructions', async () => {
    mockCreateOrder.mockRejectedValueOnce(
      new ApiError({
        status: 504,
        code: 'GATEWAY_TIMEOUT',
        message: 'Gateway Timeout',
      }),
    );

    await renderScreen();

    await waitFor(() => {
      expect(screen.queryByText('Loading restaurant settings')).toBeNull();
      expect(screen.getByText('Place pickup order')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Place pickup order'));
    });

    await waitFor(() => {
      expect(screen.getByText('Submission Status Uncertain')).toBeTruthy();
    });

    const input = screen.getByLabelText('Special instructions');
    await act(async () => {
      fireEvent.changeText(input, 'Extra tartar sauce please');
    });

    await waitFor(() => {
      expect(screen.getByText('Submission Status Uncertain')).toBeTruthy();
      expect(screen.queryByText('Place pickup order')).toBeNull();
    });
  });
  it('restores the uncertainty block after application restart', async () => {
    const user = (useAuth as jest.Mock)().user;
    await AsyncStorage.setItem(
      'lgb.checkout.pending.' + toAccountNamespace(user.email),
      'pending',
    );
    await renderScreen();
    await waitFor(() =>
      expect(screen.getByText('Submission Status Uncertain')).toBeTruthy(),
    );
    expect(mockCreateOrder).not.toHaveBeenCalled();
  });
});
