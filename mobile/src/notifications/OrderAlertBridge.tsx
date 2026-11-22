import type { NotificationResponse } from 'expo-notifications';
import { useEffect } from 'react';
import { router } from 'expo-router';
import { useAuth } from '../auth/AuthContext';
import { fetchOrder } from '../api/orders';
import { orderAlertsAvailable, refreshOrderAlerts } from './orderAlerts';
import { AppState } from 'react-native';
import { onAccountChange } from '../auth/lifecycle';
import { loadNotifications } from './nativeModule';
export function OrderAlertBridge() {
  const { user, status } = useAuth();
  useEffect(() => {
    if (!orderAlertsAvailable || status !== 'signedIn' || !user) return;
    let live = true;
    const stop = onAccountChange(() => { live = false; remove?.(); });
    let remove: (() => void) | undefined;
    const seen = new Set<string>();
    const refresh = () => { if (live) void refreshOrderAlerts(user.email, user.token).catch(() => {}); };
    const appState = AppState.addEventListener('change', state => { if (state === 'active') refresh(); });
    refresh();
    void loadNotifications()
      .then((Notifications) => {
        const open = async (response: NotificationResponse) => {
          if (!live) return;
          const data = response.notification.request.content.data ?? {};
          const id = data.orderId,
            event = data.eventId;
          if (
            typeof id !== 'string' ||
            !/^[A-Za-z0-9_-]{1,64}$/.test(id) ||
            typeof event !== 'string' ||
            seen.has(event)
          )
            return;
          seen.add(event);
          try {
            await fetchOrder(id, user.token);
            if (live) {
              await Notifications.clearLastNotificationResponseAsync();
              if (live) router.push('/order/' + id);
            }
          } catch {
            /* Ownership, expiry and session checks must pass before navigation. */
          }
        };
        const subscription =
          Notifications.addNotificationResponseReceivedListener((response) => {
            void open(response);
          });
        remove = () => subscription.remove();
        if (!live) {
          remove();
          return;
        }
        void Notifications.getLastNotificationResponseAsync().then(
          (response) => {
            if (response && live) void open(response);
          },
          () => {},
        );
      })
      .catch(() => {});
    return () => {
      live = false;
      stop();
      appState.remove();
      remove?.();
    };
  }, [user, status]);
  return null;
}
