'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { notFound, useRouter } from 'next/navigation';
import { io } from 'socket.io-client';
import { ChevronDown, Clock3, LifeBuoy, NotebookPen, Phone, RotateCcw, Star, X } from 'lucide-react';
import { CartItem, useCart } from '@/context/CartContext';
import {
  API_BASE,
  CustomerOrder,
  ORDER_TRACKING_TOKEN_HEADER,
  SOCKET_URL,
  formatMoney,
  formatOrderId,
  formatPickupDateLabel,
  formatPickupTimeLabel,
  getStoredTrackingToken,
  orderStatusLabel,
} from '@/lib/orders';
import { RESTAURANT_PHONE_DISPLAY, RESTAURANT_PHONE_TEL } from '@/lib/phone';

function statusBadgeClass(status: CustomerOrder['status'], styleMap: Record<string, string>): string {
  if (status === 'NEW') return styleMap.statusNew;
  if (status === 'ACCEPTED') return styleMap.statusAccepted;
  if (status === 'READY') return styleMap.statusReady;
  return styleMap.statusPickedUp;
}
import { getAuthHeadersOnly } from '@/lib/auth';
import styles from './order-status.module.css';
import OrderMessages from '@/components/OrderMessages';

function buildOrderAccessHeaders(orderId: string): HeadersInit {
  const headers: Record<string, string> = {};
  const authHeaders = getAuthHeadersOnly() as Record<string, string>;
  Object.assign(headers, authHeaders);
  const trackingToken = getStoredTrackingToken(orderId);
  if (trackingToken) {
    headers[ORDER_TRACKING_TOKEN_HEADER] = trackingToken;
  }
  return headers;
}

function formatTimestamp(value?: string | null) {
  if (!value) {
    return 'Not yet';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function formatOrdinalDay(day: number): string {
  const rem10 = day % 10;
  const rem100 = day % 100;
  if (rem10 === 1 && rem100 !== 11) return `${day}st`;
  if (rem10 === 2 && rem100 !== 12) return `${day}nd`;
  if (rem10 === 3 && rem100 !== 13) return `${day}rd`;
  return `${day}th`;
}

function formatPickedUpDateLabel(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  if (!year || !month || !day) {
    return isoDate;
  }
  const monthName = new Date(year, month - 1, day).toLocaleDateString(undefined, { month: 'long' });
  return `${formatOrdinalDay(day)} ${monthName}`;
}

function formatPickedUpTimeLabel(value?: string | null): string {
  if (!value) {
    return '';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

type OrderStatusClientProps = {
  orderId: string;
};

export default function OrderStatusClient({ orderId }: OrderStatusClientProps) {
  const router = useRouter();
  const { replaceCart } = useCart();
  const [order, setOrder] = useState<CustomerOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewItems, setReviewItems] = useState<Record<string, { rating: number; description: string }>>({});
  const [submittingReview, setSubmittingReview] = useState(false);
  const [timelineOpen, setTimelineOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [missing, setMissing] = useState(false);

  const applyOrder = useCallback((nextOrder: CustomerOrder) => {
    if (nextOrder.id === orderId) {
      setOrder(nextOrder);
    }
  }, [orderId]);

  const loadOrder = useCallback(async (showLoading = false) => {
    if (showLoading) {
      setLoading(true);
    }
    try {
      const res = await fetch(`${API_BASE}/orders/${orderId}`, {
        headers: buildOrderAccessHeaders(orderId),
        cache: 'no-store',
      });
      if (res.status === 404) {
        setMissing(true);
        return;
      }
      if (!res.ok) {
        throw new Error('Order load failed.');
      }
      setOrder(await res.json());
    } catch {
      if (showLoading) {
        setNotice('Order could not be loaded.');
      }
    } finally {
      if (showLoading) {
        setLoading(false);
      }
    }
  }, [orderId]);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      void loadOrder(true);
    }, 0);
    return () => window.clearTimeout(timeout);
  }, [loadOrder]);

  useEffect(() => {
    const authHeaders = getAuthHeadersOnly() as Record<string, string>;
    const jwt = authHeaders.Authorization?.replace(/^Bearer\s+/i, '') || '';
    const socket = io(SOCKET_URL, {
      query: { orderId },
      auth: {
        token: jwt,
        trackingToken: getStoredTrackingToken(orderId) || '',
      },
      transports: ['websocket', 'polling'],
    });

    const handleConnect = () => {
      // Re-fetch after every connection so status changes missed while offline
      // are reflected immediately without a browser refresh.
      void loadOrder();
    };

    socket.on('connect', handleConnect);
    socket.on('order.updated', applyOrder);

    return () => {
      socket.off('connect', handleConnect);
      socket.off('order.updated', applyOrder);
      socket.disconnect();
    };
  }, [applyOrder, loadOrder, orderId]);

  const timeline = useMemo(() => {
    if (!order) {
      return [];
    }

    return [
      { label: 'Order received', value: order.createdAt },
      { label: 'Kitchen accepted', value: order.acceptedAt },
      { label: 'Ready for pickup', value: order.readyAt },
      { label: 'Picked up', value: order.pickedUpAt },
    ];
  }, [order]);
  const hasReviewInput = useMemo(
    () => Object.values(reviewItems).some(item => item.rating > 0 || item.description.trim().length > 0),
    [reviewItems],
  );

  const initializeReview = (nextOrder: CustomerOrder) => {
    setReviewItems(nextOrder.items.reduce<Record<string, { rating: number; description: string }>>((acc, item) => {
      acc[item.menuItemId] = { rating: 0, description: '' };
      return acc;
    }, {}));
    setReviewOpen(true);
  };

  const reorderItems = () => {
    if (!order) {
      return;
    }

    const cartItems: CartItem[] = order.items.map(item => ({
      id: item.menuItemId,
      name: item.name,
      description: '',
      category: 'Reorder',
      image: '',
      price: item.unitPrice,
      quantity: item.quantity,
    }));

    replaceCart(cartItems);
    router.push('/checkout');
  };

  const submitReview = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!order || !hasReviewInput) {
      return;
    }

    setSubmittingReview(true);
    setNotice('');
    try {
      const reviewedItems = order.items
        .map(item => ({
          menuItemId: item.menuItemId,
          itemName: item.name,
          rating: reviewItems[item.menuItemId]?.rating || 0,
          description: (reviewItems[item.menuItemId]?.description || '').trim(),
        }))
        .filter(item => item.rating > 0 || item.description.length > 0);

      const res = await fetch(`${API_BASE}/orders/${order.id}/reviews`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...buildOrderAccessHeaders(order.id),
        },
        body: JSON.stringify({
          items: reviewedItems,
        }),
      });

      if (!res.ok) {
        throw new Error('Review failed.');
      }

      setReviewOpen(false);
      setOrder(prev => prev ? { ...prev, reviewed: true } : prev);
      setNotice('Review submitted. It will appear after approval.');
    } catch {
      setNotice('Review could not be submitted.');
    } finally {
      setSubmittingReview(false);
    }
  };

  if (missing) notFound();
  if (loading) {
    return (
      <main className={styles.container}>
        <div className={styles.loading}>Loading order...</div>
      </main>
    );
  }

  if (!order) {
    return (
      <main className={styles.container}>
        <div className={styles.loading}>{notice || 'Order not found.'}</div>
      </main>
    );
  }

  const currentStepIndex = {
    NEW: 0,
    ACCEPTED: 1,
    READY: 2,
    PICKED_UP: 3,
    NO_SHOW: 0,
  }[order.status];
  const isAsap = order.pickupTime?.trim().toLowerCase() === 'asap';
  const isPickedUp = order.status === 'PICKED_UP';
  const pickedUpTimeLabel = formatPickedUpTimeLabel(order.pickedUpAt);

  return (
    <main className={styles.container}>
      <header className={styles.header}>
        <p className={styles.eyebrow}>Order Status</p>
        <h1>#{formatOrderId(order.id)}</h1>
        <div className={styles.orderMeta}>
          {isPickedUp ? (
            <>
              <span>Picked up</span>
              <span>{formatPickedUpDateLabel(order.pickupDate)}</span>
              {pickedUpTimeLabel && <span>{pickedUpTimeLabel}</span>}
            </>
          ) : (
            <>
              <span>{formatPickupDateLabel(order.pickupDate)}</span>
              <span>{formatPickupTimeLabel(order.pickupTime)}</span>
              {isAsap && <span>About {order.averageReadyMinutes} min</span>}
            </>
          )}
        </div>
        {!isPickedUp && (
          <div className={styles.statusRow}>
            <span className={`${styles.statusBadge} ${statusBadgeClass(order.status, styles)}`}>
              {orderStatusLabel(order.status)}
            </span>
          </div>
        )}
      </header>

      <section className={styles.actionBar}>
        <button type="button" onClick={() => setHelpOpen(true)}>
          <LifeBuoy size={18} /> Help
        </button>
        {order.status === 'PICKED_UP' && (
          <>
            <button type="button" onClick={reorderItems}>
              <RotateCcw size={18} /> Reorder
            </button>
            {!order.reviewed && (
              <button type="button" onClick={() => initializeReview(order)}>
                <Star size={18} /> Review items
              </button>
            )}
          </>
        )}
      </section>

      {notice && <p className={styles.notice}>{notice}</p>}

      {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
        <section className={styles.specialPanel}>
          <h2><NotebookPen size={18} /> Special Instructions</h2>
          <p>{order.specialInstructions}</p>
        </section>
      )}

      <section className={styles.summary}>
        <h2>Order Summary</h2>
        <ul className={styles.itemList}>
          {order.items.map((item, index) => (
            <li key={`${item.menuItemId}-${index}`}>
              <div className={styles.itemDetails}>
                <span>{item.quantity}x {item.name}</span>
                <small>{formatMoney(item.unitPrice)} each</small>
              </div>
              <strong>{formatMoney(item.total)}</strong>
            </li>
          ))}
        </ul>
        <div className={styles.totals}>
          <div><span>Subtotal</span><strong>{formatMoney(order.subtotal)}</strong></div>
          <div><span>Tax</span><strong>{formatMoney(order.tax)}</strong></div>
          <div className={styles.grandTotal}><span>Total</span><strong>{formatMoney(order.total)}</strong></div>
        </div>
      </section>

      <section className={styles.timelinePanel} aria-live="polite">
        <button
          type="button"
          className={styles.timelineToggle}
          onClick={() => setTimelineOpen(open => !open)}
          aria-expanded={timelineOpen}
        >
          <span><Clock3 size={18} /> Order updates</span>
          <ChevronDown className={timelineOpen ? styles.chevronOpen : ''} size={18} />
        </button>
        {timelineOpen && (
          <div className={styles.timeline}>
            {timeline.map((step, index) => (
              <div
                key={step.label}
                className={[
                  styles.timelineStep,
                  index <= currentStepIndex ? styles.timelineStepComplete : '',
                  index === currentStepIndex ? styles.timelineStepActive : '',
                ].filter(Boolean).join(' ')}
              >
                <span className={styles.timelineMarker} aria-hidden />
                <div>
                  <strong>{step.label}</strong>
                  <span>
                    {step.value
                      ? formatTimestamp(step.value)
                      : index === currentStepIndex
                        ? 'In progress'
                        : 'Waiting'}
                  </span>
                </div>
              </div>
            ))}
            {order.status === 'NO_SHOW' && (
              <div className={`${styles.timelineStep} ${styles.timelineStepNoShow}`}>
                <span className={styles.timelineMarker} aria-hidden />
                <div>
                  <strong>Marked as no-show</strong>
                  <span>{formatTimestamp(order.noShowAt)}</span>
                </div>
              </div>
            )}
          </div>
        )}
      </section>

      <OrderMessages orderId={orderId} messages={order.messages || []} onSent={() => { void loadOrder(); }} />
      {reviewOpen && (
        <div className={styles.modalBackdrop} onClick={() => setReviewOpen(false)}>
          <form className={styles.modal} onSubmit={submitReview} onClick={event => event.stopPropagation()}>
            <div className={styles.modalHeader}>
              <div>
                <p className={styles.eyebrow}>Review</p>
                <h2>Review Items</h2>
              </div>
              <button type="button" className={styles.closeButton} onClick={() => setReviewOpen(false)} aria-label="Close review">
                <X size={18} />
              </button>
            </div>

            <div className={styles.reviewList}>
              {order.items.map(item => (
                <div className={styles.reviewItem} key={item.menuItemId}>
                  <div>
                    <strong>{item.name}</strong>
                    <span>{item.quantity} ordered</span>
                  </div>
                  <div className={styles.starRow} aria-label={`Rating for ${item.name}`}>
                    {[1, 2, 3, 4, 5].map(star => (
                      <button
                        type="button"
                        key={star}
                        className={star <= (reviewItems[item.menuItemId]?.rating || 0) ? styles.starActive : ''}
                        onClick={() => setReviewItems(prev => ({
                          ...prev,
                          [item.menuItemId]: { rating: star, description: prev[item.menuItemId]?.description || '' },
                        }))}
                        aria-label={`${star} stars`}
                      >
                        <Star size={20} />
                      </button>
                    ))}
                  </div>
                  <textarea
                    value={reviewItems[item.menuItemId]?.description || ''}
                    onChange={event => setReviewItems(prev => ({
                      ...prev,
                      [item.menuItemId]: { rating: prev[item.menuItemId]?.rating || 0, description: event.target.value },
                    }))}
                    placeholder="Optional description"
                    rows={2}
                  />
                </div>
              ))}
            </div>

            <button className={styles.modalSubmit} type="submit" disabled={submittingReview || !hasReviewInput}>
              {submittingReview ? 'Publishing...' : 'Publish review'}
            </button>
          </form>
        </div>
      )}

      {helpOpen && (
        <div className={styles.modalBackdrop} onClick={() => setHelpOpen(false)}>
          <div className={styles.modal} onClick={event => event.stopPropagation()}>
            <div className={styles.modalHeader}>
              <div>
                <p className={styles.eyebrow}>Need a hand?</p>
                <h2>Contact the Restaurant</h2>
              </div>
              <button type="button" className={styles.closeButton} onClick={() => setHelpOpen(false)} aria-label="Close help">
                <X size={18} />
              </button>
            </div>

            <div className={styles.helpBody}>
              <p>Questions about your order? Give us a call and reference order <strong>#{formatOrderId(order.id)}</strong>.</p>
              <a className={styles.callButton} href={`tel:${RESTAURANT_PHONE_TEL}`}>
                <Phone size={18} /> Call {RESTAURANT_PHONE_DISPLAY}
              </a>
            </div>
          </div>
        </div>
      )}

    </main>
  );
}
