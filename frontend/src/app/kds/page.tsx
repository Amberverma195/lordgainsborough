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
  WifiOff,
  X,
} from 'lucide-react';
import { useRequireKdsAccess } from '@/hooks/useRequireKdsAccess';
import { kdsSocketAuth } from '@/lib/kdsSocketAuth';
import { KdsOrderSync, otherPickupDates, KDS_DISCONNECTED_POLL_INTERVAL_MS } from '@/lib/kdsOrderSync';
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
  const [allOrders, setAllOrders] = useState<CustomerOrder[]>([]);
  const [apiError, setApiError] = useState('');
  const [synchronized, setSynchronized] = useState(false);
  const syncRef = useRef(new KdsOrderSync());
  const orders = useMemo(() => allOrders.filter(order => order.pickupDate === selectedDate), [allOrders, selectedDate]);
  const otherDates = useMemo(() => otherPickupDates(allOrders, selectedDate), [allOrders, selectedDate]);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const [connected, setConnected] = useState(false);
  const [ordersModalOpen, setOrdersModalOpen] = useState(false);
  const [soundEnabled, setSoundEnabled] = useState(false);
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [confirmNoShowOrder, setConfirmNoShowOrder] = useState<CustomerOrder | null>(null);
  const [currentTime, setCurrentTime] = useState<string>('');

  const socketRef = useRef<Socket | null>(null);
  const soundEnabledRef = useRef(soundEnabled);
  const modalDateInputRef = useRef<HTMLInputElement>(null);
  const fetchOrdersRef = useRef<() => void>(() => {});
  const updatingRef = useRef<string | null>(null);
  const connectedRef = useRef(connected);

  useEffect(() => {
    soundEnabledRef.current = soundEnabled;
  }, [soundEnabled]);

  useEffect(() => {
    connectedRef.current = connected;
  }, [connected]);

  // Real-time clock for kitchen operators (America/Toronto)
  useEffect(() => {
    const updateTime = () => {
      try {
        const now = new Date();
        const formatted = new Intl.DateTimeFormat('en-US', {
          timeZone: 'America/Toronto',
          hour: 'numeric',
          minute: '2-digit',
          second: '2-digit',
          hour12: true,
        }).format(now);
        setCurrentTime(formatted);
      } catch {
        // Fallback to local time if timeZone fails
        setCurrentTime(new Date().toLocaleTimeString());
      }
    };
    updateTime();
    const timer = window.setInterval(updateTime, 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!ordersModalOpen && !confirmNoShowOrder) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (updatingRef.current || updatingId) return;
        setOrdersModalOpen(false);
        setConfirmNoShowOrder(null);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [ordersModalOpen, confirmNoShowOrder, updatingId]);

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

  const upsertOrder = useCallback((payload: CustomerOrder | CustomerOrder[]) => {
    const order = Array.isArray(payload) ? payload[0] : payload;
    if (!order?.id) return;

    syncRef.current.receive(order);
    setAllOrders(syncRef.current.values());
  }, []);

  const handleNewOrder = useCallback((payload: CustomerOrder | CustomerOrder[]) => {
    const order = Array.isArray(payload) ? payload[0] : payload;
    if (!order?.id) return;

    const duplicate = syncRef.current.has(order.id);
    if (!duplicate && soundEnabledRef.current) {
      playNewOrderChime();
    }
    syncRef.current.receive(order, true);
    setAllOrders(syncRef.current.values());
  }, []);

  const fetchOrders = useCallback(async () => {
    const request = syncRef.current.begin();
    setLoading(true);
    try {
      const url = `${KDS_API_BASE}/kds/orders?date=${encodeURIComponent(selectedDate)}&includeOutstanding=true`;
      const res = await apiFetch(url, { cache: 'no-store', signal: AbortSignal.timeout(15_000) });

      if (!res.ok) {
        console.warn(`[KDS Orders] Fetch failed with status ${res.status}`);
        throw new Error('Unable to load KDS orders.');
      }

      const data = await res.json();
      if (!Array.isArray(data)) throw new Error('Invalid KDS order list');
      if (syncRef.current.complete(request, data)) {
        setAllOrders(syncRef.current.values());
        setApiError('');
        setSynchronized(true);
      }
    } catch {
      if (syncRef.current.isCurrent(request)) {
        setApiError('API failure: orders could not be loaded. Displayed tickets may be out of date. Refresh to retry.');
        setSynchronized(false);
      }
    } finally {
      if (syncRef.current.isCurrent(request)) setLoading(false);
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

  // Poll while disconnected or recovering an API failure; stop once both are healthy.
  useEffect(() => {
    if (!access.authorized || (connected && !apiError)) return;

    const interval = window.setInterval(() => {
      fetchOrdersRef.current();
    }, KDS_DISCONNECTED_POLL_INTERVAL_MS);

    return () => window.clearInterval(interval);
  }, [access.authorized, connected, apiError]);

  useEffect(() => {
    if (!access.authorized) return;

    const handleFocus = () => {
      if (!connectedRef.current) {
        fetchOrdersRef.current();
      }
    };

    window.addEventListener('focus', handleFocus);
    return () => window.removeEventListener('focus', handleFocus);
  }, [access.authorized]);

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
      connectedRef.current = true;
      setSynchronized(false);
      fetchOrdersRef.current();
    };

    const handleDisconnect = (reason?: string) => {
      setConnected(false);
      connectedRef.current = false;
      setSynchronized(false);
      if (process.env.NODE_ENV !== 'production' && reason) {
        console.warn(`[KDS Socket] Disconnected: ${reason}`);
      }
    };
    const handleConnectError = (error?: Error) => {
      setConnected(false);
      connectedRef.current = false;
      setSynchronized(false);
      const msg = error?.message || 'Connection error';
      if (msg.includes('Order access denied') || msg.includes('denied')) {
        console.warn('[KDS Socket] Socket connection rejected: Order access denied');
      } else {
        console.warn(`[KDS Socket] Socket connect_error: ${msg}`);
      }
    };

    const removeDeletedOrder = ({ id }: { id: string }) => {
      syncRef.current.remove(id);
      setAllOrders(syncRef.current.values());
    };

    socket.on('connect', handleConnect);
    socket.on('disconnect', handleDisconnect);
    socket.on('connect_error', handleConnectError);
    socket.on('order.created', handleNewOrder);
    socket.on('order.updated', upsertOrder);
    socket.on('order.deleted', removeDeletedOrder);

    return () => {
      socket.off('connect', handleConnect);
      socket.off('disconnect', handleDisconnect);
      socket.off('connect_error', handleConnectError);
      socket.off('order.created', handleNewOrder);
      socket.off('order.updated', upsertOrder);
      socket.off('order.deleted', removeDeletedOrder);
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
    if (updatingRef.current || updatingId) return;
    updatingRef.current = order.id;
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
      setConfirmNoShowOrder(null);
    } catch {
      setNotice(`Could not update order #${formatOrderId(order.id)}. Please try again.`);
    } finally {
      updatingRef.current = null;
      setUpdatingId(null);
    }
  };

  const adjustReadyTime = async (order: CustomerOrder, delta: number) => {
    if (updatingRef.current || updatingId) return;
    const current = order.averageReadyMinutes || 25;
    const next = clampReadyMinutes(current + delta);
    if (next === current) return;

    updatingRef.current = order.id;
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
      updatingRef.current = null;
      setUpdatingId(null);
    }
  };

  // -------------------------------------------------------------------------
  // Access Gate Rendering
  // -------------------------------------------------------------------------
  if (access.status === 'loading') {
    return (
      <main className={styles.container}>
        <div className={styles.accessContainer}>
          <div className={styles.accessCard}>
            <div className={styles.accessIconWrap}>
              <ChefHat size={32} />
            </div>
            <p className={styles.accessEyebrow}>Lord Gainsborough KDS</p>
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
  // Main Operational KDS Board Rendering
  // -------------------------------------------------------------------------
  return (
    <main className={styles.container}>
      {/* 1. Header Redesign: Compact Black Navigation Bar (#161616) */}
      <header className={styles.toolbar} aria-label="Kitchen Display Toolbar">
        <div className={styles.brandGroup}>
          <h1 className={styles.brandTitle}>
            <ChefHat size={22} color="#D62828" />
            <span>Lord Gainsborough</span>
          </h1>
          <span className={styles.brandBadge}>KDS</span>
        </div>

        <div className={styles.headerMiddleGroup}>
          <div className={styles.headerDatePill}>
            <Calendar size={14} color="#D62828" />
            <span>Pickup: <strong>{selectedDate}</strong></span>
          </div>

          {currentTime && (
            <div className={styles.currentTimePill} title="Current time (America/Toronto)">
              <Clock3 size={14} />
              <span>{currentTime}</span>
            </div>
          )}
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
            className={`${styles.connPill} ${
              connected
                ? apiError
                  ? styles.connError
                  : synchronized && !loading
                  ? styles.connOnline
                  : styles.connSyncing
                : styles.connOffline
            }`}
            title={
              connected
                ? apiError
                  ? 'Socket connected but API order synchronization failed'
                  : synchronized && !loading
                  ? 'Socket.IO live and order list fully synchronized'
                  : 'Socket.IO connected; synchronizing orders'
                : 'Socket.IO disconnected; retrying with 20-second fallback polling'
            }
          >
            <span className={styles.connDot} />
            <span className={styles.connStatusText}>
              {connected ? (
                apiError ? (
                  <>
                    <span>Connected</span> · <span>API failure</span>
                  </>
                ) : synchronized && !loading ? (
                  <>
                    <span className={styles.connLiveWord}>Live</span> · <span>synchronized</span>
                  </>
                ) : (
                  <span>Connected · synchronizing</span>
                )
              ) : (
                <span>Disconnected · polling every 20s</span>
              )}
            </span>
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

      <div className={styles.mainContent}>
        {/* 2. Order Statistics Summary Area: Simple rectangular cards */}
        <section className={styles.statsSection} aria-label="Order Statistics">
          <div className={`${styles.statCard} ${newOrders.length > 0 ? styles.statCardUrgent : ''}`}>
            <div className={styles.statCardHeader}>
              <h3 className={styles.statCardTitle}>New</h3>
              <span className={styles.statCardSubLabel}>Orders</span>
            </div>
            <div className={styles.statCardValue}>{newOrders.length}</div>
          </div>

          <div className={styles.statCard}>
            <div className={styles.statCardHeader}>
              <h3 className={styles.statCardTitle}>Preparing</h3>
              <span className={styles.statCardSubLabel}>In Kitchen</span>
            </div>
            <div className={styles.statCardValue}>{preparingOrders.length}</div>
          </div>

          <div className={`${styles.statCard} ${styles.statCardReadyAccent}`}>
            <div className={styles.statCardHeader}>
              <h3 className={styles.statCardTitle}>Ready for Pickup</h3>
              <span className={styles.statCardSubLabel}>Awaiting Guest</span>
            </div>
            <div className={styles.statCardValue}>{readyOrders.length}</div>
          </div>

          <button
            type="button"
            className={`${styles.statCard} ${styles.statCardClickable}`}
            onClick={() => setOrdersModalOpen(true)}
            title="View selected date's order history"
            aria-label={`Total for ${selectedDate}: ${orders.length} orders`}
          >
            <div className={styles.statCardHeader}>
              <span className={styles.statCardTitle}>Total for {selectedDate}</span>
              <span className={styles.statCardSubLabel}>Order History</span>
            </div>
            <strong className={styles.statCardValue}>{orders.length}</strong>
          </button>
        </section>

        {/* Pickup Date Queue Selection Toolbar */}
        <div className={styles.queueToolbar}>
          <label>
            Pickup date (America/Toronto):{' '}
            <input
              type="date"
              value={selectedDate}
              onChange={event => handleDateChange(event.target.value)}
              aria-label="Board pickup date"
            />
            <strong> {selectedDate}{selectedDate === getTodayString() ? ' · Today' : ' · Scheduled pickup queue'}</strong>
          </label>
          <button type="button" className={styles.dateJumpBtn} onClick={() => handleDateChange(getTodayString())}>
            Today
          </button>
        </div>

        {apiError && <div className={styles.noticeBanner} role="alert">{apiError}</div>}

        {otherDates.length > 0 && (
          <div className={styles.queueToolbar} aria-label="Orders awaiting pickup on other dates">
            <span>Orders awaiting pickup on other dates:</span>
            {otherDates.map(([date, count]) => (
              <button key={date} type="button" className={styles.dateJumpBtn} onClick={() => handleDateChange(date)}>
                {date}: {count} order{count === 1 ? '' : 's'}
              </button>
            ))}
          </div>
        )}

        {!loading && !apiError && orders.length === 0 && (
          <div className={styles.queueToolbar} role="status">No orders for selected date {selectedDate}.</div>
        )}

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

        {/* 3. Main Operational Columns: NEW ORDERS | PREPARING | READY FOR PICKUP */}
        <section className={styles.board} aria-busy={loading} aria-label="Order Columns">
          {/* COLUMN 1: NEW ORDERS */}
          <div className={`${styles.column} ${styles.columnNew}`}>
            <div className={styles.colHeader}>
              <div className={styles.colHeaderLeft}>
                <Clock3 size={18} className={styles.colHeaderIcon} />
                <h2>New Orders</h2>
              </div>
              <span className={`${styles.colBadge} ${styles.colBadgeNew}`}>{newOrders.length}</span>
            </div>

            <div className={styles.ticketStack}>
              {newOrders.map(order => {
                const isBusy = updatingId === order.id;
                const isAsap = (order.pickupTime || '').toLowerCase().includes('asap');

                return (
                  <article className={`${styles.ticketCard} ${styles.ticketCardNew} ${isAsap ? styles.ticketCardAsap : ''}`} key={order.id}>
                    <div className={styles.ticketTop}>
                      <div className={styles.ticketIdGroup}>
                        <h3 className={styles.ticketId}>#{formatOrderId(order.id)}</h3>
                        {isAsap && <span className={styles.urgentBadge}>ASAP</span>}
                      </div>
                      <span className={styles.ticketPlacedTime}>Placed {formatTime(order.createdAt)}</span>
                    </div>

                    <div className={styles.ticketCustomer}>
                      <div className={styles.customerHeader}>
                        <span className={styles.customerName}>{order.customerName}</span>
                        <a href={`tel:${order.customerPhone}`} className={styles.customerPhone} aria-label={`Call ${order.customerPhone}`}>
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
                      <div className={`${styles.pickupTimePill} ${isAsap ? styles.pickupTimePillAsap : ''}`}>
                        <Calendar size={14} />
                        <span>Pickup: <strong>{isAsap ? 'ASAP' : order.pickupTime}</strong></span>
                      </div>
                      <span className={styles.orderTotalDisplay}>
                        {formatMoney(order.total)}
                      </span>
                    </div>

                    {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
                      <div className={styles.specialBox}>
                        <NotebookPen size={16} className={styles.specialIcon} />
                        <div className={styles.specialContent}>
                          <strong className={styles.specialLabel}>SPECIAL INSTRUCTIONS:</strong>
                          <span className={styles.specialText}>{order.specialInstructions}</span>
                        </div>
                      </div>
                    )}

                    <ul className={styles.itemsList}>
                      {order.items.map((item, idx) => (
                        <li key={`${order.id}-${item.menuItemId}-${idx}`} className={styles.itemRow}>
                          <div className={styles.itemInfo}>
                            <span className={styles.itemQty}>{item.quantity}×</span>
                            <span className={styles.itemName}>{item.name}</span>
                          </div>
                        </li>
                      ))}
                    </ul>

                    <div className={styles.actionRow}>
                      <button
                        type="button"
                        className={styles.btnAccept}
                        onClick={() => updateOrderStatus(order, 'ACCEPTED')}
                        disabled={Boolean(updatingId)}
                        aria-label="Accept"
                      >
                        <Check size={18} />
                        <span>{isBusy ? 'Accepting…' : 'Accept Order'}</span>
                      </button>
                    </div>
                  </article>
                );
              })}

              {!loading && !apiError && newOrders.length === 0 && (
                <div className={styles.emptyColumn}>
                  <Clock3 size={28} />
                  <p>No new orders</p>
                </div>
              )}
            </div>
          </div>

          {/* COLUMN 2: PREPARING (ACCEPTED) */}
          <div className={`${styles.column} ${styles.columnPreparing}`}>
            <div className={styles.colHeader}>
              <div className={styles.colHeaderLeft}>
                <ChefHat size={18} className={styles.colHeaderIcon} />
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
                  <article className={`${styles.ticketCard} ${styles.ticketCardPreparing}`} key={order.id}>
                    <div className={styles.ticketTop}>
                      <h3 className={styles.ticketId}>#{formatOrderId(order.id)}</h3>
                      <span className={styles.ticketPlacedTime}>Accepted {formatTime(order.acceptedAt || order.createdAt)}</span>
                    </div>

                    <div className={styles.ticketCustomer}>
                      <div className={styles.customerHeader}>
                        <span className={styles.customerName}>{order.customerName}</span>
                        <a href={`tel:${order.customerPhone}`} className={styles.customerPhone} aria-label={`Call ${order.customerPhone}`}>
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
                        <Clock3 size={14} />
                        <span>Target: <strong>{order.pickupTime}</strong></span>
                      </div>

                      <div className={styles.prepControls} aria-label="Adjust preparation minutes">
                        <button
                          type="button"
                          className={styles.prepBtn}
                          onClick={() => adjustReadyTime(order, -5)}
                          disabled={!canDecrease || Boolean(updatingId)}
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
                          disabled={!canIncrease || Boolean(updatingId)}
                          title="Increase prep time by 5 minutes"
                          aria-label="Increase ready time by 5 minutes"
                        >
                          +5
                        </button>
                      </div>
                    </div>

                    {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
                      <div className={styles.specialBox}>
                        <NotebookPen size={16} className={styles.specialIcon} />
                        <div className={styles.specialContent}>
                          <strong className={styles.specialLabel}>SPECIAL INSTRUCTIONS:</strong>
                          <span className={styles.specialText}>{order.specialInstructions}</span>
                        </div>
                      </div>
                    )}

                    <ul className={styles.itemsList}>
                      {order.items.map((item, idx) => (
                        <li key={`${order.id}-${item.menuItemId}-${idx}`} className={styles.itemRow}>
                          <div className={styles.itemInfo}>
                            <span className={styles.itemQty}>{item.quantity}×</span>
                            <span className={styles.itemName}>{item.name}</span>
                          </div>
                        </li>
                      ))}
                    </ul>

                    <div className={styles.actionRow}>
                      <button
                        type="button"
                        className={styles.btnMarkReady}
                        onClick={() => updateOrderStatus(order, 'READY')}
                        disabled={Boolean(updatingId)}
                        aria-label="Mark ready"
                      >
                        <PackageCheck size={18} />
                        <span>{isBusy ? 'Updating…' : 'Mark Ready'}</span>
                      </button>
                    </div>
                  </article>
                );
              })}

              {!loading && !apiError && preparingOrders.length === 0 && (
                <div className={styles.emptyColumn}>
                  <ChefHat size={28} />
                  <p>No orders in prep</p>
                </div>
              )}
            </div>
          </div>

          {/* COLUMN 3: READY FOR PICKUP */}
          <div className={`${styles.column} ${styles.columnReady}`}>
            <div className={styles.colHeader}>
              <div className={styles.colHeaderLeft}>
                <PackageCheck size={18} className={styles.colHeaderIcon} />
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
                      <span className={styles.ticketReadyTime}>
                        Ready at {formatTime(order.readyAt || order.createdAt)}
                      </span>
                    </div>

                    <div className={styles.ticketCustomer}>
                      <div className={styles.customerHeader}>
                        <span className={styles.customerName}>{order.customerName}</span>
                        <a href={`tel:${order.customerPhone}`} className={styles.customerPhone} aria-label={`Call ${order.customerPhone}`}>
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
                        <ShoppingBag size={14} className={styles.pickupTimeIconReady} />
                        <span>Customer Pickup</span>
                      </div>
                      <span className={styles.orderTotalDisplay}>
                        {formatMoney(order.total)}
                      </span>
                    </div>

                    {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
                      <div className={styles.specialBox}>
                        <NotebookPen size={16} className={styles.specialIcon} />
                        <div className={styles.specialContent}>
                          <strong className={styles.specialLabel}>SPECIAL INSTRUCTIONS:</strong>
                          <span className={styles.specialText}>{order.specialInstructions}</span>
                        </div>
                      </div>
                    )}

                    <ul className={styles.itemsList}>
                      {order.items.map((item, idx) => (
                        <li key={`${order.id}-${item.menuItemId}-${idx}`} className={styles.itemRow}>
                          <div className={styles.itemInfo}>
                            <span className={styles.itemQty}>{item.quantity}×</span>
                            <span className={styles.itemName}>{item.name}</span>
                          </div>
                        </li>
                      ))}
                    </ul>

                    <div className={styles.actionRow}>
                      <button
                        type="button"
                        className={styles.btnPickedUp}
                        onClick={() => updateOrderStatus(order, 'PICKED_UP')}
                        disabled={Boolean(updatingId)}
                        aria-label="Picked up"
                      >
                        <ShoppingBag size={18} />
                        <span>{isBusy ? 'Saving…' : 'Picked Up'}</span>
                      </button>
                      <button
                        type="button"
                        className={styles.btnNoShow}
                        onClick={() => setConfirmNoShowOrder(order)}
                        disabled={Boolean(updatingId)}
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

              {!loading && !apiError && readyOrders.length === 0 && (
                <div className={styles.emptyColumn}>
                  <PackageCheck size={28} />
                  <p>No orders waiting for pickup</p>
                </div>
              )}
            </div>
          </div>
        </section>
      </div>

      {/* Confirmation Modal for No-Show */}
      {confirmNoShowOrder && (
        <div
          className={styles.modalBackdrop}
          onClick={() => {
            if (!updatingRef.current && !updatingId) {
              setConfirmNoShowOrder(null);
            }
          }}
          role="presentation"
        >
          <div
            className={styles.confirmDialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="confirm-no-show-title"
            aria-describedby="confirm-no-show-desc"
            onClick={event => event.stopPropagation()}
          >
            <div className={styles.confirmHeader}>
              <UserX size={24} />
              <h3 id="confirm-no-show-title">Confirm No-Show</h3>
            </div>
            <p id="confirm-no-show-desc" className={styles.confirmBody}>
              Are you sure you want to mark order <strong>#{formatOrderId(confirmNoShowOrder.id)}</strong> as a
              no-show? This records a customer no-show against <strong>{confirmNoShowOrder.customerName}</strong> ({confirmNoShowOrder.customerPhone}) and removes the ticket from the active kitchen board.
            </p>
            <div className={styles.confirmActions}>
              <button
                type="button"
                className={styles.btnCancel}
                onClick={() => setConfirmNoShowOrder(null)}
                disabled={Boolean(updatingId)}
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

      {/* 8. Orders History Modal */}
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
                <p style={{ textAlign: 'center', color: '#555555', margin: '2rem 0', fontWeight: 600 }}>
                  {apiError ? 'Orders unavailable: API request failed.' : loading ? 'Loading orders…' : 'No orders found for this date.'}
                </p>
              ) : (
                orders.map(order => {
                  const statusClass =
                    order.status === 'READY'
                      ? styles.statusBadgeReady
                      : order.status === 'ACCEPTED'
                      ? styles.statusBadgeAccepted
                      : order.status === 'NO_SHOW'
                      ? styles.statusBadgeNoShow
                      : order.status === 'PICKED_UP'
                      ? styles.statusBadgePickedUp
                      : styles.statusBadgeNew;

                  return (
                    <article key={order.id} className={styles.ordersModalRow}>
                      <div className={styles.ordersModalRowTop}>
                        <span className={styles.modalOrderId}>#{formatOrderId(order.id)}</span>
                        <span className={`${styles.statusBadgePill} ${statusClass}`}>
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
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
