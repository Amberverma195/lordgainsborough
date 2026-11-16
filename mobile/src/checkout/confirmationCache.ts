import { CustomerOrderView } from '../api/parse';

const confirmed = new Map<string, CustomerOrderView>();

export function rememberConfirmedOrder(order: CustomerOrderView): void {
  confirmed.set(order.id, order);
}

export function readConfirmedOrder(orderId: string, customerEmail?: string | null): CustomerOrderView | null {
  const order = confirmed.get(orderId) ?? null;
  if (!order) {
    return null;
  }
  if (customerEmail && order.customerEmail) {
    if (order.customerEmail.trim().toLowerCase() !== customerEmail.trim().toLowerCase()) {
      return null;
    }
  }
  return order;
}

export function clearConfirmedOrder(orderId: string): void {
  confirmed.delete(orderId);
}

export function clearAllConfirmedOrders(): void {
  confirmed.clear();
}

