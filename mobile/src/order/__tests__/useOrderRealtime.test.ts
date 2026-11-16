import { renderHook, waitFor } from '@testing-library/react-native';

jest.mock('socket.io-client', () => {
  const m = jest.fn();
  return {
    __esModule: true,
    io: m,
    default: m,
  };
});

import { io } from 'socket.io-client';
import * as ordersApi from '../../api/orders';
import { CustomerOrderView } from '../../api/parse';
import { useOrderRealtime } from '../useOrderRealtime';

const mockIo = io as unknown as jest.Mock;

describe('useOrderRealtime hook', () => {
  let socketListeners: Record<string, (...args: unknown[]) => void>;
  let mockSocket: {
    on: jest.Mock;
    off: jest.Mock;
    disconnect: jest.Mock;
  };

  const sampleOrder: CustomerOrderView = {
    id: 'ord-123',
    pickupDate: '2026-11-20',
    pickupTime: '17:30',
    specialInstructions: 'Extra tartar sauce',
    status: 'ACCEPTED',
    averageReadyMinutes: 20,
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

  beforeEach(() => {
    jest.clearAllMocks();
    socketListeners = {};
    mockSocket = {
      on: jest.fn((event: string, callback: (...args: unknown[]) => void) => {
        socketListeners[event] = callback;
        return mockSocket;
      }),
      off: jest.fn(),
      disconnect: jest.fn(),
    };
    mockIo.mockReturnValue(mockSocket);
    jest.spyOn(ordersApi, 'fetchOrderSocketTicket').mockResolvedValue('valid-ticket-xyz');
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('connects to socket with orderId query and auth handler', async () => {
    const onConnect = jest.fn();
    const onOrderUpdated = jest.fn();

    const { unmount } = await renderHook(() =>
      useOrderRealtime({
        orderId: 'ord-123',
        token: 'user-jwt',
        trackingToken: 'track-456',
        onConnect,
        onOrderUpdated,
      }),
    );

    await waitFor(() => {
      expect(mockIo).toHaveBeenCalledWith(
        expect.stringContaining(':9092'),
        expect.objectContaining({
          query: { orderId: 'ord-123' },
          transports: ['websocket', 'polling'],
        }),
      );
    });

    // Verify auth callback fetches ticket
    const ioOptions = mockIo.mock.calls[0][1];
    const authCb = jest.fn();
    await ioOptions.auth(authCb);
    expect(ordersApi.fetchOrderSocketTicket).toHaveBeenCalledWith('ord-123', 'user-jwt', 'track-456');
    expect(authCb).toHaveBeenCalledWith({ token: 'valid-ticket-xyz' });

    // Verify connect handler
    expect(socketListeners.connect).toBeDefined();
    socketListeners.connect();
    expect(onConnect).toHaveBeenCalledTimes(1);

    // Verify order.updated handler
    expect(socketListeners['order.updated']).toBeDefined();
    socketListeners['order.updated'](sampleOrder);
    expect(onOrderUpdated).toHaveBeenCalledWith(sampleOrder);

    // Cleanup on unmount
    await unmount();
    expect(mockSocket.disconnect).toHaveBeenCalled();
  });

  it('ignores updates for a different order id', async () => {
    const onOrderUpdated = jest.fn();

    const { unmount } = await renderHook(() =>
      useOrderRealtime({
        orderId: 'ord-123',
        onOrderUpdated,
      }),
    );

    await waitFor(() => {
      expect(socketListeners['order.updated']).toBeDefined();
    });

    socketListeners['order.updated']({ ...sampleOrder, id: 'different-order' });
    expect(onOrderUpdated).not.toHaveBeenCalled();

    await unmount();
  });

  it('does not connect when enabled is false or orderId is empty', async () => {
    const hook1 = await renderHook(() =>
      useOrderRealtime({
        orderId: 'ord-123',
        enabled: false,
      }),
    );

    const hook2 = await renderHook(() =>
      useOrderRealtime({
        orderId: '',
        enabled: true,
      }),
    );

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(mockIo).not.toHaveBeenCalled();

    await hook1.unmount();
    await hook2.unmount();
  });
});
