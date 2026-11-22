import * as SecureStore from 'expo-secure-store';
import * as Crypto from 'expo-crypto';
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import { apiRequest } from '../api/client';
import { toAccountNamespace } from '../auth/accountNamespace';
import { onAccountChange } from '../auth/lifecycle';
import { loadNotifications } from './nativeModule';
export const orderAlertsAvailable =
  process.env.EXPO_PUBLIC_ORDER_NOTIFICATIONS_ENABLED === 'true';
export const pushDeviceKey = (email: string) =>
  'lgb.push-device.' + toAccountNamespace(email);
const registrations = new Map<string, Promise<void>>();
const registrationKey = (email: string, token: string) => `${pushDeviceKey(email)}:${token}`;
export function enableOrderAlerts(email: string, token: string, requestPermission = true): Promise<void> {
  const key = registrationKey(email, token);
  const existing = registrations.get(key);
  if (existing) return existing;
  const operation = registerOrderAlerts(email, token, requestPermission).finally(() => {
    if (registrations.get(key) === operation) registrations.delete(key);
  });
  registrations.set(key, operation);
  return operation;
}
async function registerOrderAlerts(email: string, token: string, requestPermission: boolean) {
  if (!orderAlertsAvailable)
    throw new Error('Order alerts are not enabled for this build.');
  let active = true;
  const stop = onAccountChange(() => { active = false; });
  const assertActive = () => { if (!active) throw new Error('Account changed. Enable alerts again after signing in.'); };
  try {
    const projectId = Constants.expoConfig?.extra?.eas?.projectId;
    if (!projectId) throw new Error('An existing Expo project association is required.');
    const Notifications = await loadNotifications();
    assertActive();
    if (Platform.OS === 'android')
      await Notifications.setNotificationChannelAsync('orders', {
        name: 'Pickup order updates',
        importance: Notifications.AndroidImportance.DEFAULT,
      });
    assertActive();
    const permission = requestPermission
      ? await Notifications.requestPermissionsAsync()
      : await Notifications.getPermissionsAsync();
    assertActive();
    if (permission.status !== 'granted') {
      if (!requestPermission) await removeOrderAlerts(email, token);
      throw new Error('Notification permission was not granted.');
    }
    const push = await Notifications.getExpoPushTokenAsync({ projectId });
    assertActive();
    const id =
      (await SecureStore.getItemAsync(pushDeviceKey(email))) ??
      Crypto.randomUUID();
    assertActive();
    // Persist identifier before registration so failure cannot orphan an enabled server registration.
    await SecureStore.setItemAsync(pushDeviceKey(email), id);
    assertActive();
    await apiRequest('/api/auth/push-devices', {
      method: 'POST',
      token,
      body: { deviceId: id, pushToken: push.data },
      notifyUnauthorized: false,
    });
    if (!active) {
      await removeOrderAlerts(email, token);
      assertActive();
    }
  } finally { stop(); }
}
/** Renew only an existing opt-in; foregrounding must never trigger a permission prompt. */
export async function refreshOrderAlerts(email: string, token: string) {
  if (!orderAlertsAvailable) return;
  let active = true;
  const stop = onAccountChange(() => { active = false; });
  try {
    const id = await SecureStore.getItemAsync(pushDeviceKey(email));
    if (active && id) await enableOrderAlerts(email, token, false);
  } finally { stop(); }
}
export async function disableOrderAlerts(email: string, token: string) {
  // An explicit opt-out must win over a foreground renewal already in flight.
  await registrations.get(registrationKey(email, token))?.catch(() => {});
  await removeOrderAlerts(email, token);
}
async function removeOrderAlerts(email: string, token: string) {
  const id = await SecureStore.getItemAsync(pushDeviceKey(email));
  if (id)
    await apiRequest('/api/auth/push-devices/' + encodeURIComponent(id), {
      method: 'DELETE',
      token,
      notifyUnauthorized: false,
    });
  await SecureStore.deleteItemAsync(pushDeviceKey(email));
}
