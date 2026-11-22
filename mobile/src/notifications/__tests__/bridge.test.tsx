import React from 'react';
import { act, render } from '@testing-library/react-native';
import { OrderAlertBridge } from '../OrderAlertBridge';
import { accountWillChange } from '../../auth/lifecycle';
import { fetchOrder } from '../../api/orders';
import { router } from 'expo-router';

const mockRemove = jest.fn();
const mockClear = jest.fn().mockResolvedValue(undefined);
let mockTap: (response: unknown) => void;
jest.mock('../orderAlerts', () => ({ orderAlertsAvailable: true, refreshOrderAlerts: jest.fn().mockResolvedValue(undefined) }));
jest.mock('../nativeModule', () => ({ loadNotifications: async () => ({
  addNotificationResponseReceivedListener: (tap: typeof mockTap) => { mockTap = tap; return { remove: mockRemove }; },
  getLastNotificationResponseAsync: async () => null,
  clearLastNotificationResponseAsync: mockClear,
}) }));
jest.mock('../../api/orders', () => ({ fetchOrder: jest.fn() }));
jest.mock('expo-router', () => ({ router: { push: jest.fn() } }));
jest.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ status: 'signedIn', user: { email: 'a@example.com', token: 'token-a' } }) }));
const response = { notification: { request: { content: { data: { orderId: 'order-123', eventId: 'event-1' } } } } };
beforeEach(() => { jest.clearAllMocks(); });

it('authorizes a tapped order, clears the launch response, and deduplicates taps', async () => {
  jest.mocked(fetchOrder).mockResolvedValue({ id: 'order-123' } as Awaited<ReturnType<typeof fetchOrder>>);
  const view = await render(<OrderAlertBridge />);
  await act(async () => { mockTap(response); mockTap(response); });
  expect(fetchOrder).toHaveBeenCalledTimes(1);
  expect(fetchOrder).toHaveBeenCalledWith('order-123', 'token-a');
  expect(mockClear).toHaveBeenCalledTimes(1);
  expect(router.push).toHaveBeenCalledWith('/order/order-123');
  await view.unmount();
});
it('never navigates when ownership validation fails', async () => {
  jest.mocked(fetchOrder).mockRejectedValue(new Error('Forbidden'));
  const view = await render(<OrderAlertBridge />);
  await act(async () => { mockTap(response); });
  expect(router.push).not.toHaveBeenCalled();
  await view.unmount();
});
it('invalidates a tap immediately on account change while authorization is unresolved', async () => {
  let finish!: (value: Awaited<ReturnType<typeof fetchOrder>>) => void;
  jest.mocked(fetchOrder).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const view = await render(<OrderAlertBridge />);
  await act(async () => { mockTap(response); });
  await act(async () => { accountWillChange(); finish({ id: 'order-123' } as Awaited<ReturnType<typeof fetchOrder>>); });
  expect(router.push).not.toHaveBeenCalled();
  expect(mockRemove).toHaveBeenCalled();
  await view.unmount();
});
