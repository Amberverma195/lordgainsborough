import { describe, expect, it } from 'vitest';
import type { CustomerOrder } from './orders';
import { formatTime, getTodayString } from './orders';
import { KdsOrderSync, otherPickupDates } from './kdsOrderSync';

const order = (id: string, pickupDate = '2026-11-22', status: CustomerOrder['status'] = 'NEW'): CustomerOrder => ({
  id, pickupDate, status, customerName: 'Customer', customerEmail: 'test@example.test', customerPhone: '5195551212',
  pickupTime: '12:00 PM', averageReadyMinutes: 25, items: [], messages: [], subtotal: 10, tax: 1.3, total: 11.3,
  createdAt: '2026-11-22T11:00:00',
});

describe('KDS snapshots and live orders', () => {
  it('keeps today, tomorrow and future pickups discoverable while separating the preparation date', () => {
    const sync = new KdsOrderSync();
    sync.complete(sync.begin(), [order('today'), order('tomorrow', '2026-11-23'), order('future', '2027-01-01')]);
    expect(sync.values().filter(o => o.pickupDate === '2026-11-22').map(o => o.id)).toEqual(['today']);
    expect(otherPickupDates(sync.values(), '2026-11-22')).toEqual([['2026-11-23', 1], ['2027-01-01', 1]]);
  });

  it('retains a creation and status update received while an older snapshot is in flight', () => {
    const sync = new KdsOrderSync();
    const request = sync.begin();
    sync.receive(order('live'), true);
    sync.receive(order('live', '2026-11-22', 'ACCEPTED'));
    sync.complete(request, []);
    expect(sync.values()).toEqual([order('live', '2026-11-22', 'ACCEPTED')]);
  });

  it('ignores superseded requests even when they finish last', () => {
    const sync = new KdsOrderSync();
    const old = sync.begin();
    const latest = sync.begin();
    sync.complete(latest, [order('latest')]);
    expect(sync.complete(old, [])).toBe(false);
    expect(sync.values().map(o => o.id)).toEqual(['latest']);
  });

  it('loads missed events on reconnect and overlays valid events received during resynchronization', () => {
    const sync = new KdsOrderSync();
    sync.receive(order('existing'));
    const reconnect = sync.begin();
    sync.receive(order('newer', '2027-01-01'), true);
    sync.complete(reconnect, [order('existing', '2026-11-22', 'READY'), order('missed', '2026-11-23')]);
    expect(sync.values().map(o => o.id).sort()).toEqual(['existing', 'missed', 'newer']);
    expect(sync.values().find(o => o.id === 'existing')?.status).toBe('READY');
  });

  it('deduplicates events and does not let replayed creation undo a status update', () => {
    const sync = new KdsOrderSync();
    sync.receive(order('one'), true);
    sync.receive(order('one', '2026-11-22', 'READY'));
    sync.receive(order('one'), true);
    sync.receive(order('one', '2026-11-22', 'READY'));
    expect(sync.values()).toEqual([order('one', '2026-11-22', 'READY')]);
  });

  it('does not resurrect deleted tickets from an in-flight snapshot', () => {
    const sync = new KdsOrderSync();
    const request = sync.begin();
    sync.remove('deleted');
    sync.complete(request, [order('deleted')]);
    expect(sync.values()).toEqual([]);
  });

  it('accepts an authoritative empty snapshot, removing tickets deleted while disconnected', () => {
    const sync = new KdsOrderSync();
    sync.receive(order('deleted-offline'));
    sync.complete(sync.begin(), []);
    expect(sync.values()).toEqual([]);
  });

  it('preserves existing tickets after a failed request and recovers on the next snapshot', () => {
    const sync = new KdsOrderSync();
    sync.receive(order('existing'));
    sync.begin(); // Failed requests never call complete.
    expect(sync.values()).toEqual([order('existing')]);
    sync.complete(sync.begin(), [order('existing'), order('missed')]);
    expect(sync.values()).toHaveLength(2);
  });

  it('keeps Accept, Ready, Picked Up, No Show and staff corrections intact', () => {
    const sync = new KdsOrderSync();
    for (const status of ['NEW', 'ACCEPTED', 'READY', 'ACCEPTED', 'READY', 'NO_SHOW', 'READY', 'PICKED_UP'] as const) {
      sync.receive(order('one', '2026-11-23', status));
      expect(sync.values()[0].status).toBe(status);
      expect(otherPickupDates(sync.values(), '2026-11-22').length).toBe(['NO_SHOW', 'PICKED_UP'].includes(status) ? 0 : 1);
    }
  });

  it('uses Toronto at the UTC date boundary and retains backend wall-clock times', () => {
    expect(getTodayString(new Date('2026-11-23T02:00:00Z'))).toBe('2026-11-22');
    expect(formatTime('2026-11-22T12:30:00')).toBe(formatTime('2026-11-22T17:30:00Z'));
  });
});
