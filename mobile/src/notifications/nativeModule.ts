/** Load native notification code only when alerts are explicitly available. */
export const loadNotifications = () => import('expo-notifications');
