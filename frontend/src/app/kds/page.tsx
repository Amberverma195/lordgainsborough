'use client';

import { apiFetch } from '@/lib/apiFetch';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { io } from 'socket.io-client';
import type { Socket } from 'socket.io-client';
import {
  AlertCircle,
  Calendar,
  CalendarDays,
  Check,
  ChefHat,
  Clock3,
  Lock,
  NotebookPen,
  PackageCheck,
  Phone,
  RefreshCw,
  ShieldAlert,
  ShoppingBag,
  UserX,
  Volume2,
  VolumeX,
  Wifi,
  WifiOff,
  X,
} from 'lucide-react';
import { useRequireKdsAccess } from '@/hooks/useRequireKdsAccess';
import { kdsSocketAuth } from '@/lib/kdsSocketAuth';
// import OrderMessages from '@/components/OrderMessages';
import {
  KDS_API_BASE,
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

const MIN_READY_MINUTES = 5;
const MAX_READY_MINUTES = 120;

function clampReadyMinutes(minutes: number): number {
  return Math.min(MAX_READY_MINUTES, Math.max(MIN_READY_MINUTES, minutes));
}

function formatCustomerOrderCount(count?: number): string {
  const safe = count && count > 0 ? count : 0;
  if (safe <= 1) return 'New customer';
  return `${safe} orders`;
}

function playNewOrderChime() {
  try {
    const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtx) return;
    const ctx = new AudioCtx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(587.33, ctx.currentTime); // D5
    osc.frequency.setValueAtTime(880, ctx.currentTime + 0.14); // A5

    gain.gain.setValueAtTime(0.25, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.45);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.45);
  } catch {
    // Audio autoplay restrictions before user interaction
  }
}

export default function KdsPage() {
  const access = useRequireKdsAccess();
  const [selectedDate, setSelectedDate] = useState(getTodayString());
  const [orders, setOrders] = useState<CustomerOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const [connected, setConnected] = useState(false);
  const [ordersModalOpen, setOrdersModalOpen] = useState(false);
  const [soundEnabled, setSoundEnabled] = useState(false);
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [confirmNoShowOrder, setConfirmNoShowOrder] = useState<CustomerOrder | null>(null);

  const socketRef = useRef<Socket | null>(null);
  const selectedDateRef = useRef(selectedDate);
  const soundEnabledRef = useRef(soundEnabled);
  const modalDateInputRef = useRef<HTMLInputElement>(null);
  const fetchOrdersRef = useRef<() => void>(() => {});

  useEffect(() => {
    selectedDateRef.current = selectedDate;
  }, [selectedDate]);

  useEffect(() => {
    soundEnabledRef.current = soundEnabled;
  }, [soundEnabled]);

  useEffect(() => {
    if (!ordersModalOpen && !confirmNoShowOrder) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOrdersModalOpen(false);
        setConfirmNoShowOrder(null);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [ordersModalOpen, confirmNoShowOrder]);

  const openModalDatePicker = () => {
    const input = modalDateInputRef.current;
    if (!input) return;
    if (typeof input.showPicker === 'function') {
      input.showPicker();
      return;
    }
    input.focus();
    input.click();
  };

  const handleDateChange = (value: string) => {
    if (!value) return;
    setSelectedDate(value);
  };

  const orderMatchesView = useCallback(
    (order: CustomerOrder) => (order.pickupDate || '').trim() === selectedDateRef.current,
    [],
  );

  const upsertOrder = useCallback((payload: CustomerOrder | CustomerOrder[]) => {
    const order = Array.isArray(payload) ? payload[0] : payload;
    if (!order?.id) return;

    setOrders(prev => {
      const withoutOrder = prev.filter(item => item.id !== order.id);
      if (!orderMatchesView(order)) {
        return withoutOrder;
      }
      return sortOrders([order, ...withoutOrder]);
    });
  }, [orderMatchesView]);

  const handleNewOrder = useCallback((payload: CustomerOrder | CustomerOrder[]) => {
    const order = Array.isArray(payload) ? payload[0] : payload;
    if (!order?.id) return;

    if (soundEnabledRef.current) {
      playNewOrderChime();
    }
    upsertOrder(order);
  }, [upsertOrder]);

  const fetchOrders = useCallback(async () => {
    setLoading(true);
    setNotice('');
    try {
      const url = `${KDS_API_BASE}/kds/orders?date=${encodeURIComponent(selectedDate)}`;
      const res = await apiFetch(url, { cache: 'no-store' });

      if (!res.ok) {
        throw new Error('Unable to load KDS orders.');
      }

      const data = await res.json();
      setOrders(sortOrders(Array.isArray(data) ? data : []));
    } catch {
      setNotice('Orders could not be loaded from the kitchen server.');
    } finally {
      setLoading(false);
    }
  }, [selectedDate]);

  useEffect(() => {
    fetchOrdersRef.current = fetchOrders;
  }, [fetchOrders]);

  useEffect(() => {
    if (access.authorized) {
      const timeout = window.setTimeout(fetchOrders, 0);
      return () => window.clearTimeout(timeout);
    }
  }, [fetchOrders, access.authorized]);

  useEffect(() => {
    if (!access.authorized) return;

    const socket = io(SOCKET_URL, {
      query: { role: 'KDS' },
      auth: kdsSocketAuth(),
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
      fetchOrdersRef.current();
    };

    const handleDisconnect = () => setConnected(false);
    const handleConnectError = () => setConnected(false);

    const removeDeletedOrder = ({ id }: { id: string }) => {
      setOrders(previous => previous.filter(order => order.id !== id));
    };

    socket.on('connect', handleConnect);
    socket.on('disconnect', handleDisconnect);
    socket.on('connect_error', handleConnectError);
    socket.on('order.created', handleNewOrder);
    socket.on('order.updated', upsertOrder);
    socket.on('order.deleted', removeDeletedOrder);
    // socket.on('chat.message', handleChatMessage); // Chat disabled for pickup-only model

    return () => {
      socket.off('connect', handleConnect);
      socket.off('disconnect', handleDisconnect);
      socket.off('connect_error', handleConnectError);
      socket.off('order.created', handleNewOrder);
      socket.off('order.updated', upsertOrder);
      socket.off('order.deleted', removeDeletedOrder);
      // socket.off('chat.message', handleChatMessage); // Chat disabled for pickup-only model
      socket.disconnect();
      socketRef.current = null;
      setConnected(false);
    };
  }, [access.authorized, handleNewOrder, upsertOrder]);

  const activeOrders = useMemo(
    () => orders.filter(order => order.status !== 'PICKED_UP' && order.status !== 'NO_SHOW'),
    [orders],
  );

  const newOrders = useMemo(
    () => activeOrders.filter(order => order.status === 'NEW'),
    [activeOrders],
  );

  const preparingOrders = useMemo(
    () => activeOrders.filter(order => order.status === 'ACCEPTED'),
    [activeOrders],
  );

  const readyOrders = useMemo(
    () => activeOrders.filter(order => order.status === 'READY'),
    [activeOrders],
  );

  const updateOrderStatus = async (order: CustomerOrder, status: OrderStatus) => {
    if (updatingId) return;
    setUpdatingId(order.id);
    setNotice('');
    try {
      const res = await apiFetch(`${KDS_API_BASE}/kds/orders/${order.id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          status,
          averageReadyMinutes: order.averageReadyMinutes || 25,
        }),
      });

      if (!res.ok) {
        throw new Error('Unable to update order status.');
      }

      upsertOrder(await res.json());
    } catch {
      setNotice(`Could not update order #${formatOrderId(order.id)}. Please try again.`);
    } finally {
      setUpdatingId(null);
      setConfirmNoShowOrder(null);
    }
  };

  const adjustReadyTime = async (order: CustomerOrder, delta: number) => {
    if (updatingId) return;
    const current = order.averageReadyMinutes || 25;
    const next = clampReadyMinutes(current + delta);
    if (next === current) return;

    setUpdatingId(order.id);
    setNotice('');
    try {
      const res = await apiFetch(`${KDS_API_BASE}/kds/orders/${order.id}/ready-time`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ averageReadyMinutes: next }),
      });

      if (!res.ok) {
        throw new Error('Unable to update ready time.');
      }

      upsertOrder(await res.json());
    } catch {
      setNotice(`Could not update ready time for order #${formatOrderId(order.id)}.`);
    } finally {
      setUpdatingId(null);
    }
  };

  // -------------------------------------------------------------------------
  // Access Gate Rendering (Phase 1)
  // -------------------------------------------------------------------------
  if (access.status === 'loading') {
    return (
      <main className={styles.container}>
        <div className={styles.accessContainer}>
          <div className={styles.accessCard}>
            <div className={styles.accessIconWrap}>
              <ChefHat size={32} />
            </div>
            <p className={styles.accessEyebrow}>Lord Gainsborough</p>
            <h1 className={styles.accessTitle}>Kitchen Display</h1>
            <p className={styles.accessText}>Verifying authorization...</p>
          </div>
        </div>
      </main>
    );
  }

  if (access.status === 'unauthenticated') {
    return (
      <main className={styles.container}>
        <div className={styles.accessContainer}>
          <div className={styles.accessCard}>
            <div className={styles.accessIconWrap}>
              <Lock size={32} />
            </div>
            <p className={styles.accessEyebrow}>Security Verification</p>
            <h1 className={styles.accessTitle}>Kitchen Display Access</h1>
            <p className={styles.accessText}>
              Staff or Administrator authentication is required to access the Lord Gainsborough Kitchen Display System.
            </p>
            <div className={styles.accessActions}>
              <Link href="/login?redirect=/kds" className={styles.accessBtnPrimary}>
                Sign In to KDS
              </Link>
              <Link href="/" className={styles.accessBtnSecondary}>
                Return to Home
              </Link>
            </div>
          </div>
        </div>
      </main>
    );
  }

  if (access.status === 'forbidden') {
    return (
      <main className={styles.container}>
        <div className={styles.accessContainer}>
          <div className={styles.accessCard}>
            <div className={`${styles.accessIconWrap} ${styles.accessIconError}`}>
              <ShieldAlert size={32} />
            </div>
            <p className={styles.accessEyebrow}>Access Restricted</p>
            <h1 className={styles.accessTitle}>Staff Authorization Required</h1>
            <p className={styles.accessText}>
              Your account is logged in as a customer. Kitchen Display access is reserved for staff and administrators.
            </p>
            <div className={styles.accessActions}>
              <Link href="/profile" className={styles.accessBtnPrimary}>
                View Account Profile
              </Link>
              <Link href="/" className={styles.accessBtnSecondary}>
                Return to Home
              </Link>
            </div>
          </div>
        </div>
      </main>
    );
  }

  if (access.status === 'error') {
    return (
      <main className={styles.container}>
        <div className={styles.accessContainer}>
          <div className={styles.accessCard}>
            <div className={`${styles.accessIconWrap} ${styles.accessIconError}`}>
              <WifiOff size={32} />
            </div>
            <p className={styles.accessEyebrow}>Connection Problem</p>
            <h1 className={styles.accessTitle}>Server Unavailable</h1>
            <p className={styles.accessText}>
              Unable to establish a secure connection to the kitchen server. Please check your network and try again.
            </p>
            <div className={styles.accessActions}>
              <button type="button" onClick={access.retry} className={styles.accessBtnPrimary}>
                <RefreshCw size={16} /> Retry Connection
              </button>
              <Link href="/" className={styles.accessBtnSecondary}>
                Return to Home
              </Link>
            </div>
          </div>
        </div>
      </main>
    );
  }

  // -------------------------------------------------------------------------
  // Main Operational KDS Board Rendering (Phases 2 - 8)
  // -------------------------------------------------------------------------
  return (
    <main className={styles.container}>
      {/* Top Header & Status Toolbar */}
      <header className={styles.toolbar} aria-label="Kitchen Display Toolbar">
        <div className={styles.brandGroup}>
          <h1 className={styles.brandTitle}>
            <ChefHat size={22} color="var(--secondary)" />
            <span>Lord Gainsborough</span>
          </h1>
          <span className={styles.brandBadge}>Kitchen Board</span>
        </div>

        <div className={styles.statsGroup}>
          <div className={`${styles.statPill} ${styles.statPillNew}`}>
            <span>New</span>
            <strong>{newOrders.length}</strong>
          </div>
          <div className={`${styles.statPill} ${styles.statPillPreparing}`}>
            <span>Preparing</span>
            <strong>{preparingOrders.length}</strong>
          </div>
          <div className={`${styles.statPill} ${styles.statPillReady}`}>
            <span>Ready</span>
            <strong>{readyOrders.length}</strong>
          </div>
          <button
            type="button"
            className={`${styles.statPill} ${styles.statPillClickable}`}
            onClick={() => setOrdersModalOpen(true)}
            title="View today's order history"
          >
            <ShoppingBag size={15} />
            <span>Total Today</span>
            <strong>{orders.length}</strong>
          </button>
        </div>

        <div className={styles.actionsGroup}>
          <button
            type="button"
            className={`${styles.toolbarBtn} ${soundEnabled ? styles.toolbarBtnActive : ''}`}
            onClick={() => setSoundEnabled(prev => !prev)}
            title={soundEnabled ? 'Chime sound is active on new orders' : 'Enable audio chime for new orders'}
            aria-label="Toggle new order sound"
          >
            {soundEnabled ? <Volume2 size={16} /> : <VolumeX size={16} />}
            <span>Sound</span>
          </button>

          <span
            className={`${styles.connPill} ${connected ? styles.connOnline : styles.connOffline}`}
            title={connected ? 'Live Socket.IO order stream connected' : 'Disconnected — attempting reconnect…'}
          >
            {connected ? <Wifi size={14} /> : <WifiOff size={14} />}
            <span>{connected ? 'Live' : 'Offline'}</span>
          </span>

          <button
            type="button"
            className={styles.toolbarBtn}
            onClick={fetchOrders}
            disabled={loading}
            aria-label="Refresh orders from server"
            title="Refresh orders"
          >
            <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />
            <span>Refresh</span>
          </button>
        </div>
      </header>

      {notice && (
        <div className={styles.noticeBanner} role="alert">
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <AlertCircle size={18} />
            <span>{notice}</span>
          </div>
          <button
            type="button"
            className={styles.noticeClose}
            onClick={() => setNotice('')}
            aria-label="Dismiss notice"
          >
            <X size={16} />
          </button>
        </div>
      )}

      {/* 3 Primary Columns: NEW ORDERS, PREPARING, READY FOR PICKUP */}
      <section className={styles.board} aria-busy={loading} aria-label="Order Columns">
        {/* COLUMN 1: NEW ORDERS */}
        <div className={styles.column}>
          <div className={styles.colHeader}>
            <div className={styles.colHeaderLeft}>
              <Clock3 size={18} color="#9cc0ff" />
              <h2>New Orders</h2>
            </div>
            <span className={`${styles.colBadge} ${styles.colBadgeNew}`}>{newOrders.length}</span>
          </div>

          <div className={styles.ticketStack}>
            {newOrders.map(order => {
              const isBusy = updatingId === order.id;
              const isAsap = (order.pickupTime || '').toLowerCase().includes('asap');

              return (
                <article className={styles.ticketCard} key={order.id}>
                  <div className={styles.ticketTop}>
                    <h3 className={styles.ticketId}>#{formatOrderId(order.id)}</h3>
                    <span className={styles.ticketPlacedTime}>Placed {formatTime(order.createdAt)}</span>
                  </div>

                  <div className={styles.ticketCustomer}>
                    <div className={styles.customerHeader}>
                      <span className={styles.customerName}>{order.customerName}</span>
                      <a href={`tel:${order.customerPhone}`} className={styles.customerPhone}>
                        <Phone size={13} style={{ display: 'inline', marginRight: '0.25rem' }} />
                        {order.customerPhone}
                      </a>
                    </div>
                    <div className={styles.customerBadges}>
                      <span className={styles.historyBadge}>{formatCustomerOrderCount(order.customerOrderCount)}</span>
                      {order.customerNoShowCount !== undefined && order.customerNoShowCount > 0 && (
                        <span className={`${styles.historyBadge} ${styles.historyBadgeAlert}`}>
                          {order.customerNoShowCount} No-show{order.customerNoShowCount > 1 ? 's' : ''}
                        </span>
                      )}
                    </div>
                  </div>

                  <div className={styles.timingRow}>
                    <div className={styles.pickupTimePill}>
                      <Calendar size={14} color="var(--secondary)" />
                      <span>Pickup: <strong>{isAsap ? 'ASAP' : order.pickupTime}</strong></span>
                    </div>
                    <span style={{ fontSize: '0.85rem', fontWeight: '700', color: 'var(--primary)' }}>
                      {formatMoney(order.total)}
                    </span>
                  </div>

                  {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
                    <div className={styles.specialBox}>
                      <NotebookPen size={15} />
                      <span>{order.specialInstructions}</span>
                    </div>
                  )}

                  <ul className={styles.itemsList}>
                    {order.items.map((item, idx) => (
                      <li key={`${order.id}-${item.menuItemId}-${idx}`} className={styles.itemRow}>
                        <div>
                          <span className={styles.itemQty}>{item.quantity}x</span>
                          <span className={styles.itemName}>{item.name}</span>
                        </div>
                      </li>
                    ))}
                  </ul>

                  {/* Chat disabled for Lord Gainsborough pickup-only model
                  <OrderMessages
                    orderId={order.id}
                    messages={order.messages || []}
                    kitchen
                    onSent={() => { void fetchOrders(); }}
                  />
                  */}

                  <div className={styles.actionRow}>
                    <button
                      type="button"
                      className={styles.btnAccept}
                      onClick={() => updateOrderStatus(order, 'ACCEPTED')}
                      disabled={isBusy}
                      aria-label="Accept"
                    >
                      <Check size={18} />
                      <span>{isBusy ? 'Accepting…' : 'Accept Order'}</span>
                    </button>
                  </div>
                </article>
              );
            })}

            {!loading && newOrders.length === 0 && (
              <div className={styles.emptyColumn}>
                <Clock3 size={28} color="rgba(255,255,255,0.2)" />
                <p>No new orders</p>
              </div>
            )}
          </div>
        </div>

        {/* COLUMN 2: PREPARING (ACCEPTED) */}
        <div className={styles.column}>
          <div className={styles.colHeader}>
            <div className={styles.colHeaderLeft}>
              <ChefHat size={18} color="var(--secondary)" />
              <h2>Preparing</h2>
            </div>
            <span className={`${styles.colBadge} ${styles.colBadgePreparing}`}>{preparingOrders.length}</span>
          </div>

          <div className={styles.ticketStack}>
            {preparingOrders.map(order => {
              const readyMinutes = order.averageReadyMinutes || 25;
              const isBusy = updatingId === order.id;
              const canDecrease = readyMinutes > MIN_READY_MINUTES;
              const canIncrease = readyMinutes < MAX_READY_MINUTES;

              return (
                <article className={styles.ticketCard} key={order.id}>
                  <div className={styles.ticketTop}>
                    <h3 className={styles.ticketId}>#{formatOrderId(order.id)}</h3>
                    <span className={styles.ticketPlacedTime}>Accepted {formatTime(order.acceptedAt || order.createdAt)}</span>
                  </div>

                  <div className={styles.ticketCustomer}>
                    <div className={styles.customerHeader}>
                      <span className={styles.customerName}>{order.customerName}</span>
                      <a href={`tel:${order.customerPhone}`} className={styles.customerPhone}>
                        <Phone size={13} style={{ display: 'inline', marginRight: '0.25rem' }} />
                        {order.customerPhone}
                      </a>
                    </div>
                  </div>

                  <div className={styles.timingRow}>
                    <div className={styles.pickupTimePill}>
                      <Clock3 size={14} color="var(--secondary)" />
                      <span>Target: <strong>{order.pickupTime}</strong></span>
                    </div>

                    <div className={styles.prepControls} aria-label="Adjust preparation minutes">
                      <button
                        type="button"
                        className={styles.prepBtn}
                        onClick={() => adjustReadyTime(order, -5)}
                        disabled={!canDecrease || isBusy}
                        title="Reduce prep time by 5 minutes"
                        aria-label="Decrease ready time by 5 minutes"
                      >
                        −5
                      </button>
                      <span className={styles.prepDisplay}>{readyMinutes}m</span>
                      <button
                        type="button"
                        className={styles.prepBtn}
                        onClick={() => adjustReadyTime(order, 5)}
                        disabled={!canIncrease || isBusy}
                        title="Increase prep time by 5 minutes"
                        aria-label="Increase ready time by 5 minutes"
                      >
                        +5
                      </button>
                    </div>
                  </div>

                  {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
                    <div className={styles.specialBox}>
                      <NotebookPen size={15} />
                      <span>{order.specialInstructions}</span>
                    </div>
                  )}

                  <ul className={styles.itemsList}>
                    {order.items.map((item, idx) => (
                      <li key={`${order.id}-${item.menuItemId}-${idx}`} className={styles.itemRow}>
                        <div>
                          <span className={styles.itemQty}>{item.quantity}x</span>
                          <span className={styles.itemName}>{item.name}</span>
                        </div>
                      </li>
                    ))}
                  </ul>

                  {/* Chat disabled for Lord Gainsborough pickup-only model
                  <OrderMessages
                    orderId={order.id}
                    messages={order.messages || []}
                    kitchen
                    onSent={() => { void fetchOrders(); }}
                  />
                  */}

                  <div className={styles.actionRow}>
                    <button
                      type="button"
                      className={styles.btnMarkReady}
                      onClick={() => updateOrderStatus(order, 'READY')}
                      disabled={isBusy}
                      aria-label="Mark ready"
                    >
                      <PackageCheck size={18} />
                      <span>{isBusy ? 'Updating…' : 'Mark Ready'}</span>
                    </button>
                  </div>
                </article>
              );
            })}

            {!loading && preparingOrders.length === 0 && (
              <div className={styles.emptyColumn}>
                <ChefHat size={28} color="rgba(255,255,255,0.2)" />
                <p>No orders in prep</p>
              </div>
            )}
          </div>
        </div>

        {/* COLUMN 3: READY FOR PICKUP */}
        <div className={styles.column}>
          <div className={styles.colHeader}>
            <div className={styles.colHeaderLeft}>
              <PackageCheck size={18} color="#66d49a" />
              <h2>Ready for Pickup</h2>
            </div>
            <span className={`${styles.colBadge} ${styles.colBadgeReady}`}>{readyOrders.length}</span>
          </div>

          <div className={styles.ticketStack}>
            {readyOrders.map(order => {
              const isBusy = updatingId === order.id;

              return (
                <article className={`${styles.ticketCard} ${styles.ticketCardReady}`} key={order.id}>
                  <div className={styles.ticketTop}>
                    <h3 className={styles.ticketId}>#{formatOrderId(order.id)}</h3>
                    <span className={styles.ticketPlacedTime} style={{ color: '#66d49a', fontWeight: '700' }}>
                      Ready at {formatTime(order.readyAt || order.createdAt)}
                    </span>
                  </div>

                  <div className={styles.ticketCustomer}>
                    <div className={styles.customerHeader}>
                      <span className={styles.customerName}>{order.customerName}</span>
                      <a href={`tel:${order.customerPhone}`} className={styles.customerPhone}>
                        <Phone size={13} style={{ display: 'inline', marginRight: '0.25rem' }} />
                        {order.customerPhone}
                      </a>
                    </div>
                  </div>

                  <div className={styles.timingRow}>
                    <div className={styles.pickupTimePill}>
                      <ShoppingBag size={14} color="#66d49a" />
                      <span>Customer Pickup</span>
                    </div>
                    <span style={{ fontSize: '0.85rem', fontWeight: '700', color: 'var(--primary)' }}>
                      {formatMoney(order.total)}
                    </span>
                  </div>

                  {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
                    <div className={styles.specialBox}>
                      <NotebookPen size={15} />
                      <span>{order.specialInstructions}</span>
                    </div>
                  )}

                  <ul className={styles.itemsList}>
                    {order.items.map((item, idx) => (
                      <li key={`${order.id}-${item.menuItemId}-${idx}`} className={styles.itemRow}>
                        <div>
                          <span className={styles.itemQty}>{item.quantity}x</span>
                          <span className={styles.itemName}>{item.name}</span>
                        </div>
                      </li>
                    ))}
                  </ul>

                  {/* Chat disabled for Lord Gainsborough pickup-only model
                  <OrderMessages
                    orderId={order.id}
                    messages={order.messages || []}
                    kitchen
                    onSent={() => { void fetchOrders(); }}
                  />
                  */}

                  <div className={styles.actionRow}>
                    <button
                      type="button"
                      className={styles.btnPickedUp}
                      onClick={() => updateOrderStatus(order, 'PICKED_UP')}
                      disabled={isBusy}
                      aria-label="Picked up"
                    >
                      <ShoppingBag size={18} />
                      <span>{isBusy ? 'Saving…' : 'Picked Up'}</span>
                    </button>
                    <button
                      type="button"
                      className={styles.btnNoShow}
                      onClick={() => setConfirmNoShowOrder(order)}
                      disabled={isBusy}
                      aria-label="No show"
                      title="Mark customer as no-show"
                    >
                      <UserX size={16} />
                      <span>No Show</span>
                    </button>
                  </div>
                </article>
              );
            })}

            {!loading && readyOrders.length === 0 && (
              <div className={styles.emptyColumn}>
                <PackageCheck size={28} color="rgba(255,255,255,0.2)" />
                <p>No orders waiting for pickup</p>
              </div>
            )}
          </div>
        </div>
      </section>

      {/* Confirmation Modal for No-Show (Phase 3) */}
      {confirmNoShowOrder && (
        <div
          className={styles.modalBackdrop}
          onClick={() => setConfirmNoShowOrder(null)}
          role="presentation"
        >
          <div
            className={styles.confirmDialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="confirm-no-show-title"
            onClick={event => event.stopPropagation()}
          >
            <div className={styles.confirmHeader}>
              <UserX size={24} />
              <h3 id="confirm-no-show-title">Confirm No-Show</h3>
            </div>
            <p className={styles.confirmBody}>
              Are you sure you want to mark order <strong>#{formatOrderId(confirmNoShowOrder.id)}</strong> as a
              no-show? This records a customer no-show against {confirmNoShowOrder.customerName} ({confirmNoShowOrder.customerPhone}) and removes the ticket from the active kitchen board.
            </p>
            <div className={styles.confirmActions}>
              <button
                type="button"
                className={styles.btnCancel}
                onClick={() => setConfirmNoShowOrder(null)}
              >
                Cancel
              </button>
              <button
                type="button"
                className={styles.btnConfirmDanger}
                onClick={() => updateOrderStatus(confirmNoShowOrder, 'NO_SHOW')}
                disabled={Boolean(updatingId)}
              >
                {updatingId ? 'Recording…' : 'Confirm No-Show'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* All Orders / History Modal (Phase 7) */}
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
                <h2 id="kds-orders-modal-title">Orders History</h2>
                <p>
                  {orders.length} order{orders.length === 1 ? '' : 's'} on {formatPickupDateLabel(selectedDate)}
                </p>
              </div>
              <div className={styles.ordersModalActions}>
                <label className={styles.datePickerWrap} onClick={openModalDatePicker}>
                  <CalendarDays size={16} />
                  <span>{formatPickupDateLabel(selectedDate)}</span>
                  <input
                    ref={modalDateInputRef}
                    type="date"
                    className={styles.dateInput}
                    value={selectedDate}
                    onChange={event => handleDateChange(event.target.value)}
                    aria-label="Select date"
                  />
                </label>
                <button
                  type="button"
                  className={styles.toolbarBtn}
                  onClick={() => setOrdersModalOpen(false)}
                  aria-label="Close history modal"
                >
                  <X size={18} />
                </button>
              </div>
            </div>

            <div className={styles.ordersModalList}>
              {orders.length === 0 ? (
                <p style={{ textAlign: 'center', color: 'var(--text-light)', margin: '2rem 0' }}>
                  No orders found for this date.
                </p>
              ) : (
                orders.map(order => (
                  <article key={order.id} className={styles.ordersModalRow}>
                    <div className={styles.ordersModalRowTop}>
                      <span className={styles.modalOrderId}>#{formatOrderId(order.id)}</span>
                      <span
                        className={styles.statusBadgePill}
                        style={{
                          color:
                            order.status === 'READY'
                              ? '#66d49a'
                              : order.status === 'ACCEPTED'
                              ? 'var(--secondary)'
                              : order.status === 'NO_SHOW'
                              ? '#ff8585'
                              : order.status === 'PICKED_UP'
                              ? '#9e9e9e'
                              : '#9cc0ff',
                        }}
                      >
                        {orderStatusLabel(order.status)}
                      </span>
                    </div>

                    <div className={styles.modalMetaGrid}>
                      <span>Customer: <strong>{order.customerName}</strong></span>
                      <span>Phone: <strong>{order.customerPhone}</strong></span>
                      <span>Pickup: <strong>{order.pickupTime}</strong></span>
                      <span>Total: <strong>{formatMoney(order.total)}</strong></span>
                    </div>

                    <ul className={styles.modalItems}>
                      {order.items.map((item, idx) => (
                        <li key={`${order.id}-${item.menuItemId}-${idx}`}>
                          {item.quantity}× {item.name}
                        </li>
                      ))}
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
