export { isActiveOrderStatus } from '../order/status';

export function formatOrderReference(id: string): string {
  return `ORD-${id.slice(-6).toUpperCase()}`;
}

export function orderStatusLabel(status: string): string {
  switch (status) {
    case 'NEW':
      return 'Received';
    case 'ACCEPTED':
      return 'In progress';
    case 'READY':
      return 'Ready for pickup';
    case 'NO_SHOW':
      return 'No show';
    case 'PICKED_UP':
      return 'Picked up';
    default:
      return status || 'Received';
  }
}

export function roleLabel(role: string): string {
  if (role === 'ADMIN') {
    return 'Administrator';
  }
  if (role === 'STAFF') {
    return 'Staff';
  }
  return 'Member';
}
