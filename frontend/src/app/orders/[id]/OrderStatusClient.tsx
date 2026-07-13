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
  getStoredTrackingToken,
  orderStatusLabel,
} from '@/lib/orders';

const RESTAURANT_PHONE_DISPLAY = '519-472-4168';
const RESTAURANT_PHONE_TEL = '+15194724168';

function statusBadgeClass(status: CustomerOrder['status'], styleMap: Record<string, string>): string {
  if (status === 'NEW') return styleMap.statusNew;
  if (status === 'ACCEPTED') return styleMap.statusAccepted;
  if (status === 'READY') return styleMap.statusReady;
  return styleMap.statusPickedUp;
}
import { getAuthHeadersOnly } from '@/lib/auth';
import styles from './order-status.module.css';

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
  const [timestampsOpen, setTimestampsOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  const applyOrder = useCallback((nextOrder: CustomerOrder) => {
    if (nextOrder.id === orderId) {
      setOrder(nextOrder);
    }
  }, [orderId]);

  useEffect(() => {
    const loadOrder = async () => {
      setLoading(true);
      try {
        const res = await fetch(`${API_BASE}/orders/${orderId}`, {
          headers: buildOrderAccessHeaders(orderId),
        });
        if (res.status === 404) {
          notFound();
          return;
        }
        if (!res.ok) {
          throw new Error('Order load failed.');
        }
        setOrder(await res.json());
      } catch {
        setNotice('Order could not be loaded.');
      } finally {
        setLoading(false);
      }
    };

    loadOrder();
  }, [orderId]);

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

    socket.on('order.updated', applyOrder);

    return () => {
      socket.off('order.updated', applyOrder);
      socket.disconnect();
    };
  }, [applyOrder, orderId]);

  const timestamps = useMemo(() => {
    if (!order) {
      return [];
    }

    return [
      { label: 'Received', value: order.createdAt },
      { label: 'Accepted', value: order.acceptedAt },
      { label: 'Ready', value: order.readyAt },
      { label: 'Picked up', value: order.pickedUpAt },
    ].filter(entry => Boolean(entry.value));
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

  return (
    <main className={styles.container}>
      <header className={styles.header}>
        <p className={styles.eyebrow}>Order Status</p>
        <h1>#{formatOrderId(order.id)}</h1>
        <div className={styles.orderMeta}>
          <span>{order.pickupDate}</span>
          <span>{order.pickupTime}</span>
          <span>{order.averageReadyMinutes} min average</span>
        </div>
        <span className={`${styles.statusBadge} ${statusBadgeClass(order.status, styles)}`}>
          {orderStatusLabel(order.status)}
        </span>
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

      <section className={styles.timestampPanel}>
        <button
          type="button"
          className={styles.timestampToggle}
          onClick={() => setTimestampsOpen(prev => !prev)}
          aria-expanded={timestampsOpen}
        >
          <span><Clock3 size={18} /> Order timestamps</span>
          <ChevronDown className={timestampsOpen ? styles.chevronOpen : ''} size={18} />
        </button>
        {timestampsOpen && (
          <div className={styles.timestampList}>
            {timestamps.map(timestamp => (
              <div key={timestamp.label}>
                <span>{timestamp.label}</span>
                <strong>{formatTimestamp(timestamp.value)}</strong>
              </div>
            ))}
          </div>
        )}
      </section>

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
