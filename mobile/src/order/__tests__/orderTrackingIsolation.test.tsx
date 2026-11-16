import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react-native';
import React, { useEffect, useState } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import * as ordersApi from '../../api/orders';
import * as secureSession from '../../auth/secureSession';
import { useAuth } from '../../auth/AuthContext';
import { useOrderRealtime } from '../useOrderRealtime';
import OrderDetailScreen from '../../app/order/[id]';
import { CustomerOrderView } from '../../api/parse';

jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const mockFocusEffect = jest.fn((cb: () => void) => {
  useEffect(() => {
    cb();
  }, [cb]);
});

jest.mock('expo-router', () => ({
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
  },
  useLocalSearchParams: () => ({ id: 'ord-iso-100' }),
  useFocusEffect: (cb: () => void) => mockFocusEffect(cb),
}));

jest.mock('../../order/useOrderRealtime', () => ({
  useOrderRealtime: jest.fn(),
}));

jest.mock('../../auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('../../auth/secureSession', () => ({
  readTrackingToken: jest.fn(),
}));

jest.mock('../../api/orders', () => ({
  fetchOrder: jest.fn(),
}));

const mockUseAuth = useAuth as jest.Mock;
const mockReadTrackingToken = secureSession.readTrackingToken as jest.Mock;
const mockFetchOrder = ordersApi.fetchOrder as jest.Mock;
const mockUseOrderRealtime = useOrderRealtime as jest.Mock;

const sampleOrder: CustomerOrderView = {
  id: 'ord-iso-100',
  pickupDate: '2026-11-20',
  pickupTime: '18:00',
  specialInstructions: '',
  status: 'ACCEPTED',
  averageReadyMinutes: 20,
  items: [
    {
      menuItemId: 'fish-chips',
      name: 'Fish & Chips',
      unitPrice: 18.0,
      quantity: 1,
      total: 18.0,
      notes: '',
    },
  ],
  subtotal: 1800,
  tax: 234,
  total: 2034,
  createdAt: '2026-11-20T17:30:00Z',
  reviewed: false,
  customerName: 'User A',
  customerPhone: '111',
  customerEmail: 'usera@example.com',
};

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

type AuthState = { status: string; user: any };
let setAuthDispatch: ((next: AuthState) => void) | null = null;

function HostScreen({ initialAuth, client }: { initialAuth: AuthState; client: QueryClient }) {
  const [authState, setAuthState] = useState<AuthState>(initialAuth);
  useEffect(() => {
    setAuthDispatch = setAuthState;
    return () => {
      setAuthDispatch = null;
    };
  }, []);

  mockUseAuth.mockReturnValue(authState);

  return (
    <SafeAreaProvider initialMetrics={initialMetrics}>
      <QueryClientProvider client={client}>
        <OrderDetailScreen />
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}

describe('OrderDetailScreen tracking credential isolation and stale-request protection', () => {
  let queryClient: QueryClient;

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();
    setAuthDispatch = null;
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
          gcTime: 0,
        },
      },
    });
    mockFetchOrder.mockResolvedValue(sampleOrder);
  });

  afterEach(async () => {
    await act(async () => {
      cleanup();
    });
    queryClient.clear();
  });

  it('prevents newly signed-in account from accessing prior account tracking credentials on screen switch', async () => {
    mockReadTrackingToken.mockImplementation(async (orderId: string, email?: string) => {
      if (email === 'usera@example.com' && orderId === 'ord-iso-100') {
        return 'token-usera';
      }
      return null;
    });

    const initialAuth: AuthState = {
      status: 'signedIn',
      user: { email: 'usera@example.com', token: 'jwt-usera', name: 'User A', phone: '111' },
    };

    render(<HostScreen initialAuth={initialAuth} client={queryClient} />);

    // Initial load for Customer A
    await waitFor(() => {
      expect(mockReadTrackingToken).toHaveBeenCalledWith('ord-iso-100', 'usera@example.com');
      expect(mockUseOrderRealtime).toHaveBeenCalledWith(
        expect.objectContaining({
          orderId: 'ord-iso-100',
          token: 'jwt-usera',
          trackingToken: 'token-usera',
          enabled: true,
        }),
      );
    });

    // Account switch: Customer B signs in on the same screen
    await act(async () => {
      setAuthDispatch?.({
        status: 'signedIn',
        user: { email: 'userb@example.com', token: 'jwt-userb', name: 'User B', phone: '222' },
      });
    });

    await waitFor(() => {
      expect(mockReadTrackingToken).toHaveBeenCalledWith('ord-iso-100', 'userb@example.com');
      // Realtime must be called with trackingToken null for User B, never User A's token!
      const lastCall = mockUseOrderRealtime.mock.calls[mockUseOrderRealtime.mock.calls.length - 1][0];
      expect(lastCall.token).toBe('jwt-userb');
      expect(lastCall.trackingToken).toBeNull();
    });
  });

  it('resets trackingToken state to null and disables realtime on logout', async () => {
    mockReadTrackingToken.mockResolvedValue('token-usera');

    const initialAuth: AuthState = {
      status: 'signedIn',
      user: { email: 'usera@example.com', token: 'jwt-usera', name: 'User A', phone: '111' },
    };

    render(<HostScreen initialAuth={initialAuth} client={queryClient} />);

    await waitFor(() => {
      expect(mockUseOrderRealtime).toHaveBeenCalledWith(
        expect.objectContaining({
          trackingToken: 'token-usera',
          enabled: true,
        }),
      );
    });

    // User logs out
    await act(async () => {
      setAuthDispatch?.({
        status: 'signedOut',
        user: null,
      });
    });

    await waitFor(() => {
      expect(screen.getByText('Sign in with the account that placed this order to view it.')).toBeTruthy();
    });

    const lastCall = mockUseOrderRealtime.mock.calls[mockUseOrderRealtime.mock.calls.length - 1][0];
    expect(lastCall.enabled).toBe(false);
    expect(lastCall.trackingToken).toBeNull();
  });

  it('stale-request protection: discards delayed token resolution across account switch', async () => {
    let resolveUserA!: (val: string | null) => void;
    const pendingPromiseA = new Promise<string | null>((res) => {
      resolveUserA = res;
    });

    mockReadTrackingToken.mockImplementation((_orderId: string, email?: string) => {
      if (email === 'usera@example.com') {
        return pendingPromiseA;
      }
      return Promise.resolve(null);
    });

    const initialAuth: AuthState = {
      status: 'signedIn',
      user: { email: 'usera@example.com', token: 'jwt-usera', name: 'User A', phone: '111' },
    };

    render(<HostScreen initialAuth={initialAuth} client={queryClient} />);

    // Wait for User A's initial lookup to initiate
    await waitFor(() => {
      expect(mockReadTrackingToken).toHaveBeenCalledWith('ord-iso-100', 'usera@example.com');
    });

    // Account switch happens while pendingPromiseA is pending
    await act(async () => {
      setAuthDispatch?.({
        status: 'signedIn',
        user: { email: 'userb@example.com', token: 'jwt-userb', name: 'User B', phone: '222' },
      });
    });

    await waitFor(() => {
      expect(mockReadTrackingToken).toHaveBeenCalledWith('ord-iso-100', 'userb@example.com');
    });

    // Now delayed User A token resolves
    await act(async () => {
      resolveUserA('delayed-stale-token-usera');
    });

    // Check that stale User A token was NOT adopted by User B
    const calls = mockUseOrderRealtime.mock.calls;
    const finalCall = calls[calls.length - 1][0];
    expect(finalCall.token).toBe('jwt-userb');
    expect(finalCall.trackingToken).toBeNull();

    // Verify none of User B's calls received User A's token
    for (const call of calls) {
      if (call[0].token === 'jwt-userb') {
        expect(call[0].trackingToken).not.toBe('delayed-stale-token-usera');
      }
    }
  });

  it('stale-request protection: discards delayed token resolution across logout', async () => {
    let resolveUserA!: (val: string | null) => void;
    const pendingPromiseA = new Promise<string | null>((res) => {
      resolveUserA = res;
    });

    mockReadTrackingToken.mockReturnValue(pendingPromiseA);

    const initialAuth: AuthState = {
      status: 'signedIn',
      user: { email: 'usera@example.com', token: 'jwt-usera', name: 'User A', phone: '111' },
    };

    render(<HostScreen initialAuth={initialAuth} client={queryClient} />);

    // Wait for User A lookup to initiate
    await waitFor(() => {
      expect(mockReadTrackingToken).toHaveBeenCalledWith('ord-iso-100', 'usera@example.com');
    });

    // Logout occurs while read is pending
    await act(async () => {
      setAuthDispatch?.({
        status: 'signedOut',
        user: null,
      });
    });

    await waitFor(() => {
      expect(screen.getByText('Sign in with the account that placed this order to view it.')).toBeTruthy();
    });

    // Delayed promise finishes
    await act(async () => {
      resolveUserA('delayed-stale-token-usera');
    });

    const finalCall = mockUseOrderRealtime.mock.calls[mockUseOrderRealtime.mock.calls.length - 1][0];
    expect(finalCall.enabled).toBe(false);
    expect(finalCall.trackingToken).toBeNull();
  });
});
