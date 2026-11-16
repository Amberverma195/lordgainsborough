export type ActiveOrderStatus = 'NEW' | 'ACCEPTED' | 'READY';
export type TerminalOrderStatus = 'PICKED_UP' | 'NO_SHOW';
export type KnownOrderStatus = ActiveOrderStatus | TerminalOrderStatus;

/**
 * Returns true if the order status is currently active / in progress
 * (received, accepted, ready for pickup) and still eligible for live updates.
 */
export function isActiveOrderStatus(status?: string | null): boolean {
  if (!status || typeof status !== 'string') {
    return false;
  }
  const normalized = status.trim().toUpperCase();
  if (!normalized) {
    return false;
  }
  return normalized !== 'PICKED_UP' && normalized !== 'NO_SHOW';
}
