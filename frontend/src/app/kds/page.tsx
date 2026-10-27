'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { io } from 'socket.io-client';
import type { Socket } from 'socket.io-client';
import { CalendarDays, Check, ChefHat, Clock3, PackageCheck, RefreshCw, ShoppingBag, UserX, Wifi, WifiOff, X } from 'lucide-react';
import { useRequireKdsAccess } from '@/hooks/useRequireKdsAccess';
import {
  API_BASE,
  CustomerOrder,
  OrderStatus,
  SOCKET_URL,
  formatMoney,
  formatOrderId,
  formatTime,
  formatPickupDateLabel,
  getTodayString,
  orderStatusLabel,
  sortOrders,
} from '@/lib/orders';

import styles from './kds.module.css';
import OrderMessages from '@/components/OrderMessages';

const READY_OPTIONS = [10, 15, 20, 25, 30, 35, 40, 45, 60];

const STATUS_COLUMNS: { key: OrderStatus; label: string }[] = [
  { key: 'NEW', label: 'New' },
  { key: 'ACCEPTED', label: 'Accepted' },
  { key: 'READY', label: 'Ready' },
];

const MIN_READY_MINUTES = 5;
const MAX_READY_MINUTES = 120;

function clampReadyMinutes(minutes: number) {
  return Math.min(MAX_READY_MINUTES, Math.max(MIN_READY_MINUTES, minutes));
}

function formatCustomerOrderCount(count?: number) {
  const safe = count && count > 0 ? count : 0;
  return safe === 1 ? '1 order' : `${safe} orders`;
}

function formatNoShowCount(count?: number) {
  const safe = count && count > 0 ? count : 0;
  return safe === 1 ? '1 no-show' : `${safe} no-shows`;
}

export default function KdsPage() {
  const isAuthorized = useRequireKdsAccess();
  const [selectedDate, setSelectedDate] = useState(getTodayString());
  const [orders, setOrders] = useState<CustomerOrder[]>([]);
  const [defaultReadyMinutes, setDefaultReadyMinutes] = useState(25);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const [connected, setConnected] = useState(false);
  const [ordersModalOpen, setOrdersModalOpen] = useState(false);
  const socketRef = useRef<Socket | null>(null);
  const selectedDateRef = useRef(selectedDate);
  const modalDateInputRef = useRef<HTMLInputElement>(null);
  const fetchOrdersRef = useRef<() => void>(() => {});


  useEffect(() => {
    selectedDateRef.current = selectedDate;
  }, [selectedDate]);

  useEffect(() => {
    if (!ordersModalOpen) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOrdersModalOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [ordersModalOpen]);

  const openModalDatePicker = () => {
    const input = modalDateInputRef.current;
    if (!input) {
      return;
    }
    if (typeof input.showPicker === 'function') {
      input.showPicker();
      return;
    }
    input.focus();
    input.click();
  };

  const handleDateChange = (value: string) => {
    if (!value) {
      return;
    }
    setSelectedDate(value);
  };

  const orderMatchesView = useCallback(
    (order: CustomerOrder) => (order.pickupDate || '').trim() === selectedDateRef.current,
    [],
  );

  const upsertOrder = useCallback((payload: CustomerOrder | CustomerOrder[]) => {
    const order = Array.isArray(payload) ? payload[0] : payload;
    if (!order?.id) {
      return;
    }
    setOrders(prev => {
      const withoutOrder = prev.filter(item => item.id !== order.id);
      if (!orderMatchesView(order)) {
        return withoutOrder;
      }
      return sortOrders([order, ...withoutOrder]);
    });
  }, [orderMatchesView]);

  const fetchOrders = useCallback(async () => {
    setLoading(true);
    setNotice('');
    try {
      const url = `${API_BASE}/kds/orders?date=${selectedDate}`;
      const res = await fetch(url, { cache: 'no-store' });

      if (!res.ok) {
        throw new Error('Unable to load KDS orders.');
      }

      const data = await res.json();
      setOrders(sortOrders(Array.isArray(data) ? data : []));
    } catch {
      setNotice('Orders could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [selectedDate]);

  useEffect(() => {
    fetchOrdersRef.current = fetchOrders;
  }, [fetchOrders]);

  useEffect(() => {
    if (isAuthorized === true) {
      const timeout = window.setTimeout(fetchOrders, 0);
      return () => window.clearTimeout(timeout);
    }
  }, [fetchOrders, isAuthorized]);

  useEffect(() => {
    if (isAuthorized !== true) {
      return;
    }

    const socket = io(SOCKET_URL, {
      query: { role: 'KDS' },
      transports: ['websocket', 'polling'],
      reconnection: true,
      reconnectionAttempts: Infinity,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 5000,
    });

    socketRef.current = socket;

    const handleConnect = () => {
      setConnected(true);
      setNotice('');
      // Always re-sync on connect so a missed order.created (or a page opened
      // before the kitchen joined the room) still appears on the board.
      fetchOrdersRef.current();
    };

    const handleDisconnect = () => setConnected(false);
    const handleConnectError = () => setConnected(false);

    socket.on('connect', handleConnect);
    socket.on('disconnect', handleDisconnect);
    socket.on('connect_error', handleConnectError);
    socket.on('order.created', upsertOrder);
    socket.on('order.updated', upsertOrder);

    return () => {
      socket.off('connect', handleConnect);
      socket.off('disconnect', handleDisconnect);
      socket.off('connect_error', handleConnectError);
      socket.off('order.created', upsertOrder);
      socket.off('order.updated', upsertOrder);
      socket.disconnect();
      socketRef.current = null;
      setConnected(false);
    };
  }, [isAuthorized, upsertOrder]);

  const visibleOrders = useMemo(
    () => orders.filter(order => order.status !== 'PICKED_UP' && order.status !== 'NO_SHOW'),
    [orders],
  );

  const counts = useMemo(() => {
    return STATUS_COLUMNS.reduce<Record<OrderStatus, number>>((acc, column) => {
      acc[column.key] = visibleOrders.filter(order => order.status === column.key).length;
      return acc;
    }, { NEW: 0, ACCEPTED: 0, READY: 0, PICKED_UP: 0, NO_SHOW: 0 });
  }, [visibleOrders]);

  const updateOrderStatus = async (order: CustomerOrder, status: OrderStatus) => {
    setNotice('');
    try {
      const res = await fetch(`${API_BASE}/kds/orders/${order.id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          status,
          averageReadyMinutes: order.averageReadyMinutes || defaultReadyMinutes,
        }),
      });

      if (!res.ok) {
        throw new Error('Unable to update order.');
      }

      upsertOrder(await res.json());
    } catch {
      setNotice('Order status could not be updated.');
    }
  };

  const updateReadyTime = async (order: CustomerOrder, minutes: number) => {
    setNotice('');
    try {
      const res = await fetch(`${API_BASE}/kds/orders/${order.id}/ready-time`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ averageReadyMinutes: clampReadyMinutes(minutes) }),
      });

      if (!res.ok) {
        throw new Error('Unable to update ready time.');
      }

      upsertOrder(await res.json());
    } catch {
      setNotice('Ready time could not be updated.');
    }
  };

  const adjustReadyTime = (order: CustomerOrder, delta: number) => {
    const current = order.averageReadyMinutes || defaultReadyMinutes;
    const next = clampReadyMinutes(current + delta);
    if (next === current) {
      return;
    }
    updateReadyTime(order, next);
  };

  if (isAuthorized === null) {
    return (
      <main className={styles.container}>
        <div className={styles.loading}>Verifying access...</div>
      </main>
    );
  }

  return (
    <main className={styles.container}>
      <section className={styles.toolbar}>
        <label className={styles.control}>
          <span><Clock3 size={16} /> Average ready time</span>
          <select value={defaultReadyMinutes} onChange={event => setDefaultReadyMinutes(Number(event.target.value))}>
            {READY_OPTIONS.map(minutes => (
              <option key={minutes} value={minutes}>{minutes} min</option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className={`${styles.stat} ${styles.statButton}`}
          onClick={() => setOrdersModalOpen(true)}
          title="View all orders for this date"
        >
          <ShoppingBag size={18} />
          <strong>{orders.length}</strong>
          <span>orders</span>
        </button>
        <div className={styles.stat}>
          <ChefHat size={18} />
          <strong>{counts.ACCEPTED}</strong>
          <span>accepted</span>
        </div>
        <div className={styles.stat}>
          <PackageCheck size={18} />
          <strong>{counts.READY}</strong>
          <span>ready</span>
        </div>
        <div className={styles.toolbarActions}>
          <span
            className={`${styles.connBadge} ${connected ? styles.connOnline : styles.connOffline}`}
            title={connected ? 'Live — connected to order stream' : 'Offline — reconnecting…'}
          >
            {connected ? <Wifi size={14} /> : <WifiOff size={14} />}
            {connected ? 'Live' : 'Offline'}
          </span>
          <button
            className={styles.iconButton}
            onClick={fetchOrders}
            aria-label="Refresh orders"
            title="Refresh orders"
          >
            <RefreshCw size={18} />
          </button>
        </div>
      </section>

      {notice && <p className={styles.notice}>{notice}</p>}

      <div className={styles.workspace}>
        <section className={styles.board} aria-busy={loading}>
          {STATUS_COLUMNS.map(column => {
            const columnOrders = visibleOrders.filter(order => order.status === column.key);
            return (
              <div className={styles.column} key={column.key}>
                <div className={styles.columnHeader}>
                  <h2>{column.label}</h2>
                  <span>{columnOrders.length}</span>
                </div>

                <div className={styles.orderStack}>
                  {columnOrders.map(order => {
                    const readyMinutes = order.averageReadyMinutes || defaultReadyMinutes;
                    const canDecrease = readyMinutes > MIN_READY_MINUTES;
                    const canIncrease = readyMinutes < MAX_READY_MINUTES;
                    const customerOrdersLabel = formatCustomerOrderCount(order.customerOrderCount);
                    const noShowLabel = formatNoShowCount(order.customerNoShowCount);

                    return (
                      <article className={styles.orderCard} key={order.id}>
                        <div className={styles.cardTopline}>
                          <strong>#{formatOrderId(order.id)}</strong>
                        </div>

                        <div className={styles.metaGrid}>
                          <span>{order.pickupTime}</span>
                          <span>{formatTime(order.createdAt)}</span>
                          <div className={styles.timeAdjust}>
                            <button
                              type="button"
                              className={styles.timeAdjustBtn}
                              onClick={() => adjustReadyTime(order, -5)}
                              disabled={!canDecrease}
                              aria-label="Decrease ready time by 5 minutes"
                            >
                              −5
                            </button>
                            <span className={styles.timeAdjustValue}>{readyMinutes}m</span>
                            <button
                              type="button"
                              className={styles.timeAdjustBtn}
                              onClick={() => adjustReadyTime(order, 5)}
                              disabled={!canIncrease}
                              aria-label="Increase ready time by 5 minutes"
                            >
                              +5
                            </button>
                          </div>
                        </div>

                        <div className={styles.customerBlock}>
                          <div className={styles.customerLine}>
                            <strong>{order.customerName}</strong>
                            <span>{order.customerPhone}</span>
                          </div>
                          <span className={styles.customerOrders}>
                            <span>{customerOrdersLabel}</span>
                            <span className={styles.customerStatDivider}>·</span>
                            <span className={order.customerNoShowCount ? styles.noShowStat : undefined}>
                              {noShowLabel}
                            </span>
                          </span>
                        </div>

                        <ul className={styles.itemList}>
                          {order.items.map((item, index) => (
                            <li key={`${order.id}-${item.menuItemId}-${index}`}>
                              <span>{item.quantity}x {item.name}</span>
                              <strong>{formatMoney(item.total)}</strong>
                            </li>
                          ))}
                        </ul>

                        <div className={styles.readyRow}>
                          <span className={styles.readyLabel}>Ready {readyMinutes} min</span>
                          <strong>{formatMoney(order.total)}</strong>
                        </div>

                        <OrderMessages orderId={order.id} messages={order.messages || []} kitchen onSent={() => { void fetchOrders(); }} />
                        <div className={styles.cardActions}>
                          {order.status === 'NEW' && (
                            <button className={styles.primaryAction} onClick={() => updateOrderStatus(order, 'ACCEPTED')}>
                              <Check size={16} /> Accept
                            </button>
                          )}
                          {order.status === 'ACCEPTED' && (
                            <button className={styles.primaryAction} onClick={() => updateOrderStatus(order, 'READY')}>
                              <PackageCheck size={16} /> Mark ready
                            </button>
                          )}
                          {order.status === 'READY' && (
                            <>
                              <button className={styles.primaryAction} onClick={() => updateOrderStatus(order, 'PICKED_UP')}>
                                <ShoppingBag size={16} /> Picked up
                              </button>
                              <button className={styles.dangerAction} onClick={() => updateOrderStatus(order, 'NO_SHOW')}>
                                <UserX size={16} /> No show
                              </button>
                            </>
                          )}
                        </div>
                      </article>
                    );
                  })}

                  {!loading && columnOrders.length === 0 && (
                    <div className={styles.emptyColumn}>No orders</div>
                  )}
                </div>
              </div>
            );
          })}
        </section>
      </div>

      {ordersModalOpen && (
        <div
          className={styles.modalBackdrop}
          onClick={() => setOrdersModalOpen(false)}
          role="presentation"
        >
          <div
            className={styles.ordersModal}
            role="dialog"
            aria-modal="true"
            aria-labelledby="kds-orders-modal-title"
            onClick={event => event.stopPropagation()}
          >
            <div className={styles.ordersModalHeader}>
              <div>
                <h2 id="kds-orders-modal-title">All orders</h2>
                <p>{orders.length} order{orders.length === 1 ? '' : 's'} for {formatPickupDateLabel(selectedDate)}</p>
              </div>
              <div className={styles.ordersModalHeaderActions}>
                <button
                  type="button"
                  className={`${styles.dateButton} ${styles.dateButtonActive}`}
                  onClick={openModalDatePicker}
                  title={`Pickup date: ${selectedDate}`}
                  aria-label="Select pickup date"
                >
                  <CalendarDays size={18} className={styles.dateIcon} />
                  <span className={styles.dateLabel}>{formatPickupDateLabel(selectedDate)}</span>
                  <input
                    ref={modalDateInputRef}
                    type="date"
                    className={styles.dateInput}
                    value={selectedDate}
                    onChange={event => handleDateChange(event.target.value)}
                    tabIndex={-1}
                    aria-hidden
                  />
                </button>
                <button
                  type="button"
                  className={styles.iconButton}
                  onClick={() => setOrdersModalOpen(false)}
                  aria-label="Close orders list"
                >
                  <X size={18} />
                </button>
              </div>
            </div>

            <div className={styles.ordersModalList}>
              {orders.length === 0 ? (
                <p className={styles.ordersModalEmpty}>No orders for this date.</p>
              ) : (
                orders.map(order => (
                  <article key={order.id} className={styles.ordersModalRow}>
                    <div className={styles.ordersModalRowTop}>
                      <strong>#{formatOrderId(order.id)}</strong>
                      <span className={styles.ordersModalStatus}>{orderStatusLabel(order.status)}</span>
                    </div>
                    <div className={styles.ordersModalMeta}>
                      <span>{order.customerName}</span>
                      <span>{order.customerPhone}</span>
                      <span>{order.pickupTime}</span>
                      <span>{formatMoney(order.total)}</span>
                    </div>
                    <ul className={styles.ordersModalItems}>
                      {order.items.slice(0, 4).map((item, index) => (
                        <li key={`${order.id}-${item.menuItemId}-${index}`}>
                          {item.quantity}× {item.name}
                        </li>
                      ))}
                      {order.items.length > 4 && (
                        <li>+{order.items.length - 4} more</li>
                      )}
                    </ul>
                  </article>
                ))
              )}
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
