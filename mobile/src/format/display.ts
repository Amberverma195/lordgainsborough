export { isActiveOrderStatus } from '../order/status';

export function formatOrderReference(id: string): string {
  return `ORD-${id.slice(-6).toUpperCase()}`;
}

export function formatOrderId(id: string): string {
  if (!id) return '';
  return id.replace(/[^a-zA-Z0-9]/g, '').slice(-6).toUpperCase();
}

export function formatProgressTimestamp(value?: string | null): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  return parsed.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  });
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
