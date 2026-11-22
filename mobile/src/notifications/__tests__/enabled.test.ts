import * as SecureStore from 'expo-secure-store';
import { apiRequest } from '../../api/client';
import { accountWillChange } from '../../auth/lifecycle';
import * as Notifications from 'expo-notifications';

jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
jest.mock('expo-crypto', () => ({ randomUUID: () => 'device-id' }));
jest.mock('expo-constants', () => ({ __esModule: true, default: { expoConfig: { extra: { eas: { projectId: 'existing-project-fixture' } } } } }));
jest.mock('../../api/client', () => ({ apiRequest: jest.fn().mockResolvedValue({ status: 204 }) }));
jest.mock('../nativeModule', () => ({ loadNotifications: () => Promise.resolve(jest.requireMock('expo-notifications')) }));
jest.mock('expo-notifications', () => ({
  setNotificationChannelAsync: jest.fn(), AndroidImportance: { DEFAULT: 3 },
  requestPermissionsAsync: jest.fn(), getExpoPushTokenAsync: jest.fn().mockResolvedValue({ data: 'ExpoPushToken[fixture]' }),
  getPermissionsAsync: jest.fn().mockResolvedValue({ status: 'granted' }),
}));

let enable: typeof import('../orderAlerts').enableOrderAlerts;
beforeAll(async () => {
  process.env.EXPO_PUBLIC_ORDER_NOTIFICATIONS_ENABLED = 'true';
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  enable = require('../orderAlerts').enableOrderAlerts;
});
beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(Notifications.requestPermissionsAsync).mockResolvedValue({ status: 'granted' } as Notifications.NotificationPermissionsStatus);
  jest.mocked(SecureStore.getItemAsync).mockResolvedValue('existing-device');
});
afterAll(() => { delete process.env.EXPO_PUBLIC_ORDER_NOTIFICATIONS_ENABLED; });

it('does not register when permission is rejected', async () => {
  jest.mocked(Notifications.requestPermissionsAsync).mockResolvedValue({ status: 'denied' } as Notifications.NotificationPermissionsStatus);
  await expect(enable('a@example.com', 'token-a')).rejects.toThrow('not granted');
  expect(apiRequest).not.toHaveBeenCalled();
});
it('reuses the account device identifier and authenticated registration', async () => {
  await enable('a@example.com', 'token-a');
  expect(apiRequest).toHaveBeenCalledWith('/api/auth/push-devices', expect.objectContaining({ token: 'token-a', body: { deviceId: 'existing-device', pushToken: 'ExpoPushToken[fixture]' }, notifyUnauthorized: false }));
});
it('coalesces overlapping registration attempts for the same account session', async () => {
  await Promise.all([enable('a@example.com', 'token-a'), enable('a@example.com', 'token-a')]);
  expect(Notifications.requestPermissionsAsync).toHaveBeenCalledTimes(1);
  expect(apiRequest).toHaveBeenCalledTimes(1);
});
it('makes opt-out win over a registration already in flight', async () => {
  let finish!: () => void;
  let started!: () => void;
  const registering = new Promise<void>(resolve => { started = resolve; });
  jest.mocked(apiRequest).mockImplementationOnce(() => {
    started();
    return new Promise(resolve => { finish = () => resolve({ status: 204, body: null, retryAfterHeader: null }); });
  });
  const enabling = enable('a@example.com', 'token-a');
  await registering;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { disableOrderAlerts } = require('../orderAlerts');
  const disabling = disableOrderAlerts('a@example.com', 'token-a');
  finish();
  await Promise.all([enabling, disabling]);
  expect(jest.mocked(apiRequest).mock.calls.map(([, options]) => options?.method)).toEqual(['POST', 'DELETE']);
  expect(SecureStore.deleteItemAsync).toHaveBeenCalled();
});
it('cancels registration if the account changes during the permission prompt', async () => {
  jest.mocked(Notifications.requestPermissionsAsync).mockImplementation(async () => {
    accountWillChange();
    return { status: 'granted' } as Notifications.NotificationPermissionsStatus;
  });
  await expect(enable('a@example.com', 'token-a')).rejects.toThrow('Account changed');
  expect(apiRequest).not.toHaveBeenCalled();
});
it('renews an existing opt-in without requesting permission', async () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { refreshOrderAlerts } = require('../orderAlerts');
  await refreshOrderAlerts('a@example.com', 'token-a');
  expect(Notifications.requestPermissionsAsync).not.toHaveBeenCalled();
  expect(Notifications.getPermissionsAsync).toHaveBeenCalledTimes(1);
  expect(apiRequest).toHaveBeenCalledTimes(1);
});
it('does nothing on foreground when the customer has not opted in', async () => {
  jest.mocked(SecureStore.getItemAsync).mockResolvedValue(null);
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { refreshOrderAlerts } = require('../orderAlerts');
  await refreshOrderAlerts('a@example.com', 'token-a');
  expect(Notifications.requestPermissionsAsync).not.toHaveBeenCalled();
  expect(apiRequest).not.toHaveBeenCalled();
});
