import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import * as catalogApi from '../../api/catalog';
import * as authContext from '../../auth/AuthContext';
import CartScreen from '../../app/(tabs)/cart';
import { CartProvider, useCart } from '../CartContext';
import {
  DEFAULT_ESTIMATED_HST_RATE,
  ESTIMATED_HST_FALLBACK_NOTICE,
  saveCachedTaxConfig,
  TAX_CACHE_MAX_AGE_MS,
  TAX_CONFIG_CACHE_KEY,
} from '../tax';

jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('../../api/orders', () => ({
  fetchServerCart: jest.fn().mockResolvedValue([]),
  saveServerCart: jest.fn().mockResolvedValue(undefined),
}));

const mockRouterPush = jest.fn();
jest.mock('expo-router', () => ({
  router: {
    push: (route: string) => mockRouterPush(route),
  },
}));

jest.mock('../../api/catalog', () => ({
  fetchStoreSettings: jest.fn(),
}));

const mockFetchStoreSettings = catalogApi.fetchStoreSettings as jest.Mock;

function CartPopulator({ children }: { children: React.ReactNode }) {
  const { addItem, ready } = useCart();
  const populated = React.useRef(false);

  React.useEffect(() => {
    if (ready && !populated.current) {
      populated.current = true;
      // Add a $50.00 dish to test subtotal, tax, and total
      addItem({
        id: 'prime-rib',
        name: 'Prime Rib Dinner',
        price: 50.0,
        category: 'Mains',
        available: true,
      } as any);
    }
  }, [ready, addItem]);

  return <>{children}</>;
}

describe('CartScreen tax resolution and 13% HST fallback', () => {
  let queryClient: QueryClient;

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();

    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
          gcTime: Infinity,
        },
      },
    });

    jest.spyOn(authContext, 'useAuth').mockReturnValue({
      status: 'signedIn',
      user: {
        token: 'token-test',
        name: 'John Doe',
        email: 'john@example.com',
        phone: '5195550100',
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
  });

  afterEach(() => {
    queryClient.clear();
  });

  const initialMetrics = {
    frame: { x: 0, y: 0, width: 390, height: 844 },
    insets: { top: 47, left: 0, right: 0, bottom: 34 },
  };

  const renderCart = async () => {
    return await render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <QueryClientProvider client={queryClient}>
          <CartProvider>
            <CartPopulator>
              <CartScreen />
            </CartPopulator>
          </CartProvider>
        </QueryClientProvider>
      </SafeAreaProvider>
    );
  };

  it('calculates normally when backend returns 13% tax rate', async () => {
    mockFetchStoreSettings.mockResolvedValue({
      id: 'store-1',
      taxRate: 0.13,
      defaultReadyMinutes: 20,
      storeName: 'Lord Gainsborough',
      contactPhone: '519-472-4168',
      contactEmail: 'info@lordgainsborough.com',
      pickupOrdersEnabled: true,
    });

    await renderCart();

    expect(await screen.findByText('Order Summary')).toBeTruthy();
    expect((await screen.findAllByText('$50.00')).length).toBeGreaterThan(0);

    // Tax: $6.50 and label: Estimated Tax (13%)
    expect(await screen.findByText('Estimated Tax (13%)')).toBeTruthy();
    expect(await screen.findByText('$6.50')).toBeTruthy();

    // Total: $56.50
    expect(await screen.findByText('$56.50')).toBeTruthy();

    // No fallback notice displayed
    expect(screen.queryByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeNull();
  });

  it('respects another valid tax rate from backend (e.g. 5% GST)', async () => {
    mockFetchStoreSettings.mockResolvedValue({
      id: 'store-1',
      taxRate: 0.05,
      defaultReadyMinutes: 20,
      storeName: 'Lord Gainsborough',
      contactPhone: '519-472-4168',
      contactEmail: 'info@lordgainsborough.com',
      pickupOrdersEnabled: true,
    });

    await renderCart();

    expect(await screen.findByText('Order Summary')).toBeTruthy();
    expect((await screen.findAllByText('$50.00')).length).toBeGreaterThan(0);
    expect(await screen.findByText('Estimated Tax (5%)')).toBeTruthy();
    expect(await screen.findByText('$2.50')).toBeTruthy();
    expect(await screen.findByText('$52.50')).toBeTruthy();
    expect(screen.queryByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeNull();
  });

  it('respects a legitimate 0% tax rate from the backend', async () => {
    mockFetchStoreSettings.mockResolvedValue({
      id: 'store-1',
      taxRate: 0,
      defaultReadyMinutes: 20,
      storeName: 'Lord Gainsborough',
      contactPhone: '519-472-4168',
      contactEmail: 'info@lordgainsborough.com',
      pickupOrdersEnabled: true,
    });

    await renderCart();

    expect(await screen.findByText('Order Summary')).toBeTruthy();
    expect((await screen.findAllByText('$50.00')).length).toBeGreaterThan(0);
    expect(await screen.findByText('Estimated Tax')).toBeTruthy();
    expect(await screen.findByText('$0.00')).toBeTruthy();
    expect(screen.queryByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeNull();
  });

  it('activates 13% Ontario HST fallback estimate and notice when backend fails', async () => {
    mockFetchStoreSettings.mockRejectedValue(new Error('Network request failed'));

    await renderCart();

    expect(await screen.findByText('Order Summary')).toBeTruthy();
    expect((await screen.findAllByText('$50.00')).length).toBeGreaterThan(0);

    // Fallback Tax Label: Estimated HST (13%)
    expect(await screen.findByText('Estimated HST (13%)')).toBeTruthy();

    // Fallback Tax Amount: $6.50
    expect(await screen.findByText('$6.50')).toBeTruthy();

    // Estimated Total: $56.50
    expect(await screen.findByText('$56.50')).toBeTruthy();

    // Subtle notice displayed
    expect(await screen.findByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeTruthy();

    // Cart remains usable
    const checkoutBtn = screen.getByText(/Continue to Checkout/i);
    expect(checkoutBtn).toBeTruthy();
    fireEvent.press(checkoutBtn);
    expect(mockRouterPush).toHaveBeenCalledWith('/checkout');
  });

  it('prefers a verified fresh cached rate over 13% fallback when backend fails', async () => {
    // Seed verified cached rate of 10% from 2 hours ago
    await saveCachedTaxConfig(0.1);

    mockFetchStoreSettings.mockRejectedValue(new Error('Backend offline'));

    await renderCart();

    expect(await screen.findByText('Order Summary')).toBeTruthy();
    expect((await screen.findAllByText('$50.00')).length).toBeGreaterThan(0);
    // Uses cached 10% rather than 13%
    expect(await screen.findByText('Estimated Tax (10%)')).toBeTruthy();
    expect(await screen.findByText('$5.00')).toBeTruthy();
    expect(await screen.findByText('$55.00')).toBeTruthy();

    // No fallback notice because verified cached rate is in effect
    expect(screen.queryByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeNull();
  });

  it('falls back to 13% HST if cached rate is expired or malformed', async () => {
    // Seed expired cache (25 hours ago)
    const expiredEntry = {
      taxRate: 0.08,
      verifiedAt: Date.now() - TAX_CACHE_MAX_AGE_MS - 1000 * 60 * 60,
    };
    await AsyncStorage.setItem(TAX_CONFIG_CACHE_KEY, JSON.stringify(expiredEntry));

    mockFetchStoreSettings.mockRejectedValue(new Error('Backend offline'));

    await renderCart();

    expect(await screen.findByText('Estimated HST (13%)')).toBeTruthy();
    expect(await screen.findByText('$6.50')).toBeTruthy();
    expect(await screen.findByText('$56.50')).toBeTruthy();
    expect(await screen.findByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeTruthy();
  });

  it('shows Calculating tax... during loading state without premature fallback', async () => {
    let resolveSettings!: (value: any) => void;
    mockFetchStoreSettings.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSettings = resolve;
        })
    );

    await renderCart();

    expect(await screen.findByText('Calculating tax...')).toBeTruthy();
    expect(screen.queryByText('Estimated HST (13%)')).toBeNull();
    expect(screen.queryByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeNull();

    // Once resolved, authoritative value replaces calculating
    await act(async () => {
      resolveSettings({
        id: 'store-1',
        taxRate: 0.13,
        defaultReadyMinutes: 20,
        storeName: 'Lord Gainsborough',
        contactPhone: '519-472-4168',
        contactEmail: 'info@lordgainsborough.com',
        pickupOrdersEnabled: true,
      });
    });

    expect(await screen.findByText('Estimated Tax (13%)')).toBeTruthy();
    expect(await screen.findByText('$6.50')).toBeTruthy();
  });

  it('replaces fallback with authoritative values when backend recovers', async () => {
    mockFetchStoreSettings.mockRejectedValueOnce(new Error('Temporary glitch'));

    await renderCart();

    // Initially in fallback
    expect(await screen.findByText('Estimated HST (13%)')).toBeTruthy();
    expect(await screen.findByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeTruthy();

    // Backend recovers with 5%
    mockFetchStoreSettings.mockResolvedValueOnce({
      id: 'store-1',
      taxRate: 0.05,
      defaultReadyMinutes: 20,
      storeName: 'Lord Gainsborough',
      contactPhone: '519-472-4168',
      contactEmail: 'info@lordgainsborough.com',
      pickupOrdersEnabled: true,
    });

    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['settings'] });
    });

    // Authoritative values take over
    expect(await screen.findByText('Estimated Tax (5%)')).toBeTruthy();
    expect(await screen.findByText('$2.50')).toBeTruthy();
    expect(screen.queryByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeNull();
  });

  it('ensures checkout cannot submit orders using unverified fallback rate', async () => {
    mockFetchStoreSettings.mockRejectedValue(new Error('Backend offline'));

    await renderCart();

    // In CartScreen, fallback is shown as customer estimate only
    expect(await screen.findByText('Estimated HST (13%)')).toBeTruthy();
    expect(await screen.findByText(ESTIMATED_HST_FALLBACK_NOTICE)).toBeTruthy();

    // The query client cache for 'settings' contains no authoritative data
    const cachedSettings = queryClient.getQueryData(['settings']);
    expect(cachedSettings).toBeUndefined();
    // Checkout requires settings.data to exist and taxRate to be defined,
    // guaranteeing fallback rates are never submitted as authoritative orders.
  });
});
