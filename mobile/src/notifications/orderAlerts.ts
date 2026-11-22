import * as SecureStore from 'expo-secure-store';
import * as Crypto from 'expo-crypto';
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import { apiRequest } from '../api/client';
import { toAccountNamespace } from '../auth/accountNamespace';
export const orderAlertsAvailable =
  process.env.EXPO_PUBLIC_ORDER_NOTIFICATIONS_ENABLED === 'true';
export const pushDeviceKey = (email: string) =>
  'lgb.push-device.' + toAccountNamespace(email);
export async function enableOrderAlerts(email: string, token: string) {
  if (!orderAlertsAvailable)
    throw new Error('Order alerts are not enabled for this build.');
  const Notifications = await import('expo-notifications');
  if (Platform.OS === 'android')
    await Notifications.setNotificationChannelAsync('orders', {
      name: 'Pickup order updates',
      importance: Notifications.AndroidImportance.DEFAULT,
    });
  const permission = await Notifications.requestPermissionsAsync();
  if (permission.status !== 'granted')
    throw new Error('Notification permission was not granted.');
  const projectId = Constants.expoConfig?.extra?.eas?.projectId;
  if (!projectId)
    throw new Error('An existing Expo project association is required.');
  const push = await Notifications.getExpoPushTokenAsync({ projectId });
  const id =
    (await SecureStore.getItemAsync(pushDeviceKey(email))) ??
    Crypto.randomUUID();
  // Persist identifier before registration so failure cannot orphan an enabled server registration.
  await SecureStore.setItemAsync(pushDeviceKey(email), id);
  await apiRequest('/api/auth/push-devices', {
    method: 'POST',
    token,
    body: { deviceId: id, pushToken: push.data },
  });
}
export async function disableOrderAlerts(email: string, token: string) {
  const id = await SecureStore.getItemAsync(pushDeviceKey(email));
  if (id)
    await apiRequest('/api/auth/push-devices/' + encodeURIComponent(id), {
      method: 'DELETE',
      token,
    });
  await SecureStore.deleteItemAsync(pushDeviceKey(email));
}
