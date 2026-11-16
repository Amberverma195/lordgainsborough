import { CustomerOrderView } from '../api/parse';

const confirmed = new Map<string, CustomerOrderView>();

export function rememberConfirmedOrder(order: CustomerOrderView): void {
  confirmed.set(order.id, order);
}

export function readConfirmedOrder(orderId: string): CustomerOrderView | null {
  return confirmed.get(orderId) ?? null;
}

export function clearConfirmedOrder(orderId: string): void {
  confirmed.delete(orderId);
}
