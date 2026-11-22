import type { NotificationResponse } from 'expo-notifications';
import { useEffect } from 'react';
import { router } from 'expo-router';
import { useAuth } from '../auth/AuthContext';
import { fetchOrder } from '../api/orders';
import { orderAlertsAvailable } from './orderAlerts';
export function OrderAlertBridge() {
  const { user, status } = useAuth();
  useEffect(() => {
    if (!orderAlertsAvailable || status !== 'signedIn' || !user) return;
    let live = true;
    let remove: (() => void) | undefined;
    const seen = new Set<string>();
    void import('expo-notifications')
      .then((Notifications) => {
        const open = async (response: NotificationResponse) => {
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
            if (live) router.push('/order/' + id);
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
        );
      })
      .catch(() => {});
    return () => {
      live = false;
      remove?.();
    };
  }, [user, status]);
  return null;
}
