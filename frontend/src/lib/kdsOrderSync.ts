import { CustomerOrder, isActiveOrderStatus, sortOrders } from './orders';

export const KDS_DISCONNECTED_POLL_INTERVAL_MS = 20_000;

type Request = { id: number; revision: number };

/** A snapshot replaces old state, with mutations received during that request replayed on top. */
export class KdsOrderSync {
  private orders = new Map<string, CustomerOrder>();
  private changes = new Map<string, { revision: number; order: CustomerOrder | null }>();
  private revision = 0;
  private requestId = 0;

  begin(): Request { return { id: ++this.requestId, revision: this.revision }; }
  isCurrent(request: Request): boolean { return request.id === this.requestId; }
  values(): CustomerOrder[] { return sortOrders([...this.orders.values()]); }
  has(id: string): boolean { return this.orders.has(id); }

  receive(order: CustomerOrder, created = false): void {
    // Socket.IO preserves event order. A duplicate creation must not undo a later update.
    if (created && this.orders.has(order.id)) return;
    this.orders.set(order.id, order);
    this.changes.set(order.id, { revision: ++this.revision, order });
  }

  remove(id: string): void {
    this.orders.delete(id);
    this.changes.set(id, { revision: ++this.revision, order: null });
  }

  complete(request: Request, snapshot: CustomerOrder[]): boolean {
    if (!this.isCurrent(request)) return false;
    const next = new Map(snapshot.map(order => [order.id, order]));
    for (const [id, change] of this.changes) {
      if (change.revision > request.revision) {
        if (change.order) next.set(id, change.order);
        else next.delete(id);
      }
    }
    this.orders = next;
    // Only the latest request can commit; its predecessors no longer need this journal.
    this.changes.clear();
    return true;
  }
}

export function otherPickupDates(orders: CustomerOrder[], selectedDate: string): [string, number][] {
  const counts = new Map<string, number>();
  for (const order of orders) {
    if (order.pickupDate !== selectedDate && isActiveOrderStatus(order.status)) {
      counts.set(order.pickupDate, (counts.get(order.pickupDate) ?? 0) + 1);
    }
  }
  return [...counts].sort(([a], [b]) => a.localeCompare(b));
}
