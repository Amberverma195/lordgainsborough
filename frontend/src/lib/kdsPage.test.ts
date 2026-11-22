// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import KdsPage from '@/app/kds/page';
import type { CustomerOrder } from './orders';

const mocks = vi.hoisted(() => ({
  fetch: vi.fn(),
  listeners: new Map<string, (...args: unknown[]) => void>(),
}));
vi.mock('@/lib/apiFetch', () => ({ apiFetch: mocks.fetch }));
vi.mock('@/hooks/useRequireKdsAccess', () => ({ useRequireKdsAccess: () => ({ authorized: true, status: 'authorized' }) }));
vi.mock('socket.io-client', () => ({ io: () => ({
  on: (event: string, callback: (...args: unknown[]) => void) => mocks.listeners.set(event, callback),
  off: (event: string) => mocks.listeners.delete(event), disconnect: vi.fn(),
}) }));

const ticket = (id: string, pickupDate = '2026-11-22'): CustomerOrder => ({
  id, pickupDate, status: 'NEW', customerName: `Customer ${id}`, customerEmail: 'test@example.test',
  customerPhone: '5195551212', pickupTime: '12:00 PM', averageReadyMinutes: 25, items: [], messages: [],
  subtotal: 10, tax: 1.3, total: 11.3, createdAt: '2026-11-22T11:00:00',
});
const response = (data: unknown, ok = true) => ({ ok, status: ok ? 200 : 503, json: async () => data });
let root: Root;
let host: HTMLDivElement;
const text = () => host.textContent ?? '';
const emit = async (event: string, value?: unknown) => { await act(async () => { mocks.listeners.get(event)?.(value); }); };
async function mount() {
  await act(async () => { root.render(React.createElement(KdsPage)); });
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
}
async function click(label: string) {
  const button = [...host.querySelectorAll('button')].find(b => b.textContent?.trim().startsWith(label));
  expect(button, `button ${label}`).toBeTruthy();
  await act(async () => { button!.click(); });
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-11-22T17:00:00Z'));
  mocks.fetch.mockReset().mockResolvedValue(response([]));
  mocks.listeners.clear();
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
});
afterEach(async () => {
  await act(async () => { root.unmount(); });
  host.remove(); vi.useRealTimers();
});

describe('KDS board loading and connectivity', () => {
  it('labels the selected Toronto date and distinguishes an authoritative empty list', async () => {
    await mount();
    expect(text()).toContain('2026-11-22 · Today');
    expect(text()).toContain('No orders for selected date 2026-11-22.');
    expect(mocks.fetch.mock.calls[0][0]).toContain('date=2026-11-22&includeOutstanding=true');
  });

  it('shows API failure rather than empty columns, even with a connected socket', async () => {
    mocks.fetch.mockResolvedValue(response(null, false));
    await mount(); await emit('connect');
    expect(text()).toContain('API failure');
    expect(text()).not.toContain('No new orders');
    expect(text()).not.toContain('No orders for selected date');
    expect(text()).not.toContain('Live · synchronized');
    await click('Total for 2026-11-22');
    expect(text()).toContain('Orders unavailable: API request failed.');
  });

  it('shows persisted future pickups on first load even when today has no orders', async () => {
    mocks.fetch.mockResolvedValue(response([ticket('scheduled', '2027-01-01')]));
    await mount();
    expect(text()).toContain('No orders for selected date 2026-11-22.');
    expect(text()).toContain('Orders awaiting pickup on other dates:');
    expect(text()).toContain('2027-01-01: 1 order');
    expect(text()).not.toContain('Customer scheduled');
  });

  it('treats a malformed API list as failure and retries while a socket is connected', async () => {
    mocks.fetch.mockResolvedValue(response({ error: 'unavailable' }));
    await mount(); await emit('connect');
    expect(text()).toContain('API failure');
    expect(text()).not.toContain('No new orders');
    mocks.fetch.mockResolvedValue(response([ticket('recovered')]));
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000); });
    expect(text()).toContain('Customer recovered');
    expect(text()).toContain('Live · synchronized');
    const calls = mocks.fetch.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(40_000); });
    expect(mocks.fetch).toHaveBeenCalledTimes(calls);
  });

  it('retains tickets on API failure and clears the failure after recovery', async () => {
    mocks.fetch.mockResolvedValueOnce(response([ticket('retained')]));
    await mount();
    mocks.fetch.mockResolvedValueOnce(response(null, false));
    await click('Refresh');
    expect(text()).toContain('Customer retained');
    expect(text()).toContain('API failure');
    mocks.fetch.mockResolvedValue(response([ticket('retained')]));
    await click('Refresh');
    expect(text()).not.toContain('API failure');
  });

  it('polls every 20 seconds while disconnected, stops on connect, and refreshes immediately on reconnect', async () => {
    await mount();
    expect(mocks.fetch).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000); });
    expect(mocks.fetch).toHaveBeenCalledTimes(2);
    await emit('connect');
    expect(mocks.fetch).toHaveBeenCalledTimes(3);
    expect(text()).toContain('Live · synchronized');
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(mocks.fetch).toHaveBeenCalledTimes(3);
    await emit('disconnect');
    expect(text()).toContain('Disconnected · polling every 20s');
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000); });
    expect(mocks.fetch).toHaveBeenCalledTimes(4);
    mocks.fetch.mockResolvedValue(response([ticket('missed')]));
    await emit('connect');
    expect(mocks.fetch).toHaveBeenCalledTimes(5);
    expect(text()).toContain('Customer missed');
  });

  it('discovers tomorrow and future orders received while open without mixing them into today', async () => {
    await mount();
    await emit('order.created', ticket('today'));
    await emit('order.created', ticket('tomorrow', '2026-11-23'));
    await emit('order.created', ticket('future', '2027-01-01'));
    expect(text()).toContain('Customer today');
    expect(text()).not.toContain('Customer tomorrow');
    expect(text()).toContain('2026-11-23: 1 order');
    expect(text()).toContain('2027-01-01: 1 order');
    mocks.fetch.mockResolvedValue(response([ticket('today'), ticket('tomorrow', '2026-11-23'), ticket('future', '2027-01-01')]));
    await click('2026-11-23: 1 order');
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(text()).toContain('2026-11-23 · Scheduled pickup queue');
    expect(text()).toContain('Customer tomorrow');
    expect(text()).not.toContain('Customer today');
  });

  it('keeps a socket ticket when a stale HTTP refresh completes and deduplicates repeated events', async () => {
    await mount();
    let finish!: (value: ReturnType<typeof response>) => void;
    mocks.fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    await click('Refresh');
    await emit('order.created', ticket('racing'));
    await emit('order.created', ticket('racing'));
    await emit('order.updated', { ...ticket('racing'), status: 'ACCEPTED' });
    await act(async () => { finish(response([])); });
    expect(text().match(/Customer racing/g)).toHaveLength(1);
    expect(host.querySelectorAll('article')).toHaveLength(1);
    expect(text()).toContain('Mark Ready');
  });
});
