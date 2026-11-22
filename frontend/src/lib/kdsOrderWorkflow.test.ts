import { afterEach, describe, expect, it, vi } from 'vitest';
import { CustomerOrder, getTodayString, isActiveOrderStatus, orderStatusLabel, RESTAURANT_TIMEZONE, sortOrders } from './orders';
import { kdsSocketAuth } from './kdsSocketAuth';

describe('KDS Order Workflow and Terminal Statuses', () => {
  it('correctly identifies active orders versus terminal orders', () => {
    expect(isActiveOrderStatus('NEW')).toBe(true);
    expect(isActiveOrderStatus('ACCEPTED')).toBe(true);
    expect(isActiveOrderStatus('READY')).toBe(true);
    expect(isActiveOrderStatus('PICKED_UP')).toBe(false);
    expect(isActiveOrderStatus('NO_SHOW')).toBe(false);
  });

  it('provides readable status labels for kitchen staff and customer tracking', () => {
    expect(orderStatusLabel('NEW')).toBe('Received');
    expect(orderStatusLabel('ACCEPTED')).toBe('In progress');
    expect(orderStatusLabel('READY')).toBe('Ready for pickup');
    expect(orderStatusLabel('PICKED_UP')).toBe('Picked up');
    expect(orderStatusLabel('NO_SHOW')).toBe('No show');
  });

  it('sorts orders with newest created orders first', () => {
    const older: CustomerOrder = {
      id: 'ord-1',
      customerName: 'Alice',
      customerEmail: 'alice@example.com',
      customerPhone: '519-555-0101',
      pickupDate: '2026-11-21',
      pickupTime: '12:00 PM',
      status: 'READY',
      averageReadyMinutes: 20,
      items: [],
      messages: [],
      subtotal: 10,
      tax: 1.3,
      total: 11.3,
      createdAt: '2026-11-21T11:00:00.000Z',
    };

    const newer: CustomerOrder = {
      ...older,
      id: 'ord-2',
      customerName: 'Bob',
      createdAt: '2026-11-21T11:30:00.000Z',
    };

    const sorted = sortOrders([older, newer]);
    expect(sorted[0].id).toBe('ord-2');
    expect(sorted[1].id).toBe('ord-1');
  });

  it('filters out terminal NO_SHOW and PICKED_UP orders from active tickets stack', () => {
    const list: CustomerOrder[] = [
      {
        id: 'ord-new',
        customerName: 'Customer 1',
        customerEmail: 'c1@example.com',
        customerPhone: '519-555-0101',
        pickupDate: '2026-11-21',
        pickupTime: '12:00 PM',
        status: 'NEW',
        averageReadyMinutes: 25,
        items: [],
        messages: [],
        subtotal: 10,
        tax: 1.3,
        total: 11.3,
        createdAt: '2026-11-21T11:00:00.000Z',
      },
      {
        id: 'ord-ready',
        customerName: 'Customer 2',
        customerEmail: 'c2@example.com',
        customerPhone: '519-555-0102',
        pickupDate: '2026-11-21',
        pickupTime: '12:15 PM',
        status: 'READY',
        averageReadyMinutes: 25,
        items: [],
        messages: [],
        subtotal: 15,
        tax: 1.95,
        total: 16.95,
        createdAt: '2026-11-21T11:05:00.000Z',
      },
      {
        id: 'ord-noshow',
        customerName: 'Customer 3',
        customerEmail: 'c3@example.com',
        customerPhone: '519-555-0103',
        pickupDate: '2026-11-21',
        pickupTime: '12:30 PM',
        status: 'NO_SHOW',
        averageReadyMinutes: 25,
        items: [],
        messages: [],
        subtotal: 20,
        tax: 2.6,
        total: 22.6,
        createdAt: '2026-11-21T11:10:00.000Z',
      },
      {
        id: 'ord-pickedup',
        customerName: 'Customer 4',
        customerEmail: 'c4@example.com',
        customerPhone: '519-555-0104',
        pickupDate: '2026-11-21',
        pickupTime: '12:45 PM',
        status: 'PICKED_UP',
        averageReadyMinutes: 25,
        items: [],
        messages: [],
        subtotal: 12,
        tax: 1.56,
        total: 13.56,
        createdAt: '2026-11-21T11:15:00.000Z',
      },
    ];

    const active = list.filter(order => isActiveOrderStatus(order.status));
    expect(active.map(o => o.id)).toEqual(['ord-new', 'ord-ready']);
  });

  it('configures automatic fallback polling for disconnected state to 20 seconds', async () => {
    const { KDS_DISCONNECTED_POLL_INTERVAL_MS } = await import('./kdsOrderSync');
    expect(KDS_DISCONNECTED_POLL_INTERVAL_MS).toBe(20_000);
  });

  describe('Restaurant Timezone (America/Toronto) and Date Sync', () => {
    it('uses America/Toronto timezone for getTodayString()', () => {
      expect(RESTAURANT_TIMEZONE).toBe('America/Toronto');
    });

    it('handles late-evening UTC boundary without shifting to tomorrow in Toronto', () => {
      // 2026-11-22 at 02:00:00 UTC is 2026-11-21 at 21:00:00 EST in Toronto
      const lateUtc = new Date('2026-11-22T02:00:00.000Z');
      expect(getTodayString(lateUtc)).toBe('2026-11-21');
    });

    it('correctly shifts to next date only when midnight passes in Toronto', () => {
      // 2026-11-22 at 05:01:00 UTC is 2026-11-22 at 00:01:00 EST in Toronto
      const nextDayUtc = new Date('2026-11-22T05:01:00.000Z');
      expect(getTodayString(nextDayUtc)).toBe('2026-11-22');
    });
  });

  describe('KDS Socket Auth and Error Diagnostics', () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    it('delivers 60-second ticket payload on successful retrieval', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ ticket: 'mock-kds-ticket-token' }),
      }));

      const authFn = kdsSocketAuth();
      const payload = await new Promise(resolve => authFn(resolve));
      expect(payload).toEqual({ token: 'mock-kds-ticket-token' });
    });

    it('returns error and null token on 404 unauthorized / unauthenticated', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
      }));

      const authFn = kdsSocketAuth();
      const payload = await new Promise(resolve => authFn(resolve));
      expect(payload).toEqual({ token: null, error: 'AUTH_FAILED_404' });
    });

    it('returns error and null token on 401/403', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
      }));

      const authFn = kdsSocketAuth();
      const payload = await new Promise(resolve => authFn(resolve));
      expect(payload).toEqual({ token: null, error: 'AUTH_FAILED_403' });
    });

    it('returns server error and null token on 500', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
      }));

      const authFn = kdsSocketAuth();
      const payload = await new Promise(resolve => authFn(resolve));
      expect(payload).toEqual({ token: null, error: 'SERVER_ERROR_500' });
    });

    it('returns network error and null token on fetch failure', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Network connection dropped')));

      const authFn = kdsSocketAuth();
      const payload = await new Promise(resolve => authFn(resolve));
      expect(payload).toEqual({ token: null, error: 'NETWORK_ERROR' });
    });

    it('returns error when ticket response payload is missing ticket property', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ other: 'data' }),
      }));

      const authFn = kdsSocketAuth();
      const payload = await new Promise(resolve => authFn(resolve));
      expect(payload).toEqual({ token: null, error: 'MISSING_TICKET' });
    });
  });
});
