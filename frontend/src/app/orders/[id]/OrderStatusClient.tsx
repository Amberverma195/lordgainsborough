'use client';

import { apiFetch } from '@/lib/apiFetch';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { notFound, useRouter } from 'next/navigation';
import Link from 'next/link';
import { io } from 'socket.io-client';
import { orderSocketAuth } from '@/lib/orderSocketAuth';
import {
  AlertCircle,
  Calendar,
  Check,
  ChevronDown,
  Clock3,
  LifeBuoy,
  MapPin,
  NotebookPen,
  Phone,
  ReceiptText,
  RotateCcw,
  ShoppingBag,
  Star,
  X,
} from 'lucide-react';
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
import { getAuthHeadersOnly } from '@/lib/auth';
import { MenuItem } from '@/lib/mockData';
import { planReorder, storeReorderNotice } from '@/lib/reorder';
// import OrderMessages from '@/components/OrderMessages';
import styles from './order-status.module.css';

function statusBadgeClass(status: CustomerOrder['status'], styleMap: Record<string, string>): string {
  if (status === 'NEW') return styleMap.statusNew;
  if (status === 'ACCEPTED') return styleMap.statusAccepted;
  if (status === 'READY') return styleMap.statusReady;
  if (status === 'NO_SHOW') return styleMap.statusNoShow;
  return styleMap.statusPickedUp;
}


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
  const [reordering, setReordering] = useState(false);

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
      const res = await apiFetch(`${API_BASE}/orders/${orderId}`, {
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
    const socket = io(SOCKET_URL, {
      query: { orderId },
      auth: orderSocketAuth(orderId, getStoredTrackingToken(orderId)),
      transports: ['websocket', 'polling'],
    });

    const handleConnect = () => {
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

  const reorderItems = async () => {
    if (!order || !order.items || order.items.length === 0 || reordering) {
      return;
    }

    setReordering(true);
    setNotice('');

    try {
      const res = await apiFetch(`${API_BASE}/menu`, { cache: 'no-store' });
      if (!res.ok) {
        throw new Error('Menu fetch failed');
      }

      const currentMenu: MenuItem[] = await res.json();
      const plan = planReorder(order.items, currentMenu);

      if (plan.availableItems.length === 0) {
        setNotice('None of the items from this order are currently available on the menu.');
        setReordering(false);
        return;
      }

      if (plan.notices.length > 0) {
        storeReorderNotice(plan.notices.join(' '));
      }

      replaceCart(plan.availableItems);
      router.push('/checkout');
    } catch {
      setNotice('Unable to refresh menu prices. Please try again.');
      setReordering(false);
    }
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

      const res = await apiFetch(`${API_BASE}/orders/${order.id}/reviews`, {
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
        <div className={styles.loadingContainer}>
          <div className={styles.skeletonHero} />
          <div className={styles.skeletonGrid}>
            <div className={styles.skeletonCard} />
            <div className={styles.skeletonCard} />
          </div>
          <div className={styles.loadingText}>Loading order details...</div>
        </div>
      </main>
    );
  }

  if (!order) {
    return (
      <main className={styles.container}>
        <div className={styles.errorCard}>
          <h2>Order Not Found</h2>
          <p>{notice || 'The requested order could not be found or you do not have permission to view it.'}</p>
          <Link href="/" className={styles.backHomeBtn}>Return to Home</Link>
        </div>
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

  const progressSteps = [
    { title: 'Order Placed', time: order.createdAt },
    { title: 'In Kitchen', time: order.acceptedAt },
    { title: 'Ready for Pickup', time: order.readyAt },
    { title: 'Picked Up', time: order.pickedUpAt },
  ];

  return (
    <main className={styles.container}>
      {notice && (
        <div className={styles.noticeBanner} role="alert">
          <AlertCircle size={18} />
          <span>{notice}</span>
        </div>
      )}

      {/* Hero Section */}
      <section className={styles.heroCard} aria-label="Order Status Header">
        <div className={styles.heroSingleRow}>
          <h1 className={styles.orderHeading}>#{formatOrderId(order.id)}</h1>
          <div className={styles.heroMetaPills}>
            <div className={styles.metaPill}>
              <Calendar size={15} />
              <span>{isPickedUp ? formatPickedUpDateLabel(order.pickupDate) : formatPickupDateLabel(order.pickupDate)}</span>
            </div>
            <div className={styles.metaPill}>
              <Clock3 size={15} />
              <span>{isPickedUp ? (pickedUpTimeLabel || 'Completed') : formatPickupTimeLabel(order.pickupTime)}</span>
            </div>
            {isAsap && !isPickedUp && (
              <div className={`${styles.metaPill} ${styles.metaPillHighlight}`}>
                <Clock3 size={15} />
                <span>Estimated {order.averageReadyMinutes} mins</span>
              </div>
            )}
          </div>
          <span className={`${styles.statusBadge} ${statusBadgeClass(order.status, styles)}`}>
            {orderStatusLabel(order.status)}
          </span>
        </div>
      </section>

      {/* 2-Column Responsive Layout */}
      <div className={styles.layoutGrid}>
        {/* Main Column */}
        <div className={styles.mainColumn}>
          {/* Receipt / Order Summary Card */}
          <section className={styles.card} aria-label="Order Summary">
            <div className={styles.cardHeader}>
              <h2><ReceiptText size={18} /> Order Summary</h2>
              <span className={styles.headerBadge}>
                {order.items.reduce((acc, it) => acc + it.quantity, 0)} {order.items.reduce((acc, it) => acc + it.quantity, 0) === 1 ? 'item' : 'items'}
              </span>
            </div>

            <ul className={styles.itemsList}>
              {order.items.map((item, index) => (
                <li key={`${item.menuItemId}-${index}`} className={styles.itemRow}>
                  <div className={styles.itemMain}>
                    <span className={styles.itemQty}>{item.quantity}x</span>
                    <div className={styles.itemMeta}>
                      <span className={styles.itemName}>{item.name}</span>
                      <span className={styles.itemUnitPrice}>{formatMoney(item.unitPrice)} each</span>
                    </div>
                  </div>
                  <strong className={styles.itemPrice}>{formatMoney(item.total)}</strong>
                </li>
              ))}
            </ul>

            <div className={styles.totalsDivider} />

            <div className={styles.totalsSection}>
              <div className={styles.totalsRow}>
                <span>Subtotal</span>
                <strong>{formatMoney(order.subtotal)}</strong>
              </div>
              <div className={styles.totalsRow}>
                <span>Tax</span>
                <strong>{formatMoney(order.tax)}</strong>
              </div>
              <div className={styles.grandTotalRow}>
                <span>Total Paid</span>
                <strong>{formatMoney(order.total)}</strong>
              </div>
            </div>
          </section>

          {/* Special Instructions */}
          {order.specialInstructions && order.specialInstructions.trim().length > 0 && (
            <section className={styles.specialCard} aria-label="Special Instructions">
              <h2><NotebookPen size={18} /> Special Instructions</h2>
              <p>{order.specialInstructions}</p>
            </section>
          )}

          {/* Chat disabled for Lord Gainsborough pickup-only model
          <OrderMessages
            orderId={orderId}
            messages={order.messages || []}
            onSent={() => { void loadOrder(); }}
          />
          */}

          {/* Tracker Card / Order Progress */}
          <section className={styles.card} aria-label="Order Progress">
            <div className={styles.cardHeader}>
              <h2><Clock3 size={18} /> Order Progress</h2>
              <span className={styles.headerBadge}>{orderStatusLabel(order.status)}</span>
            </div>

            {order.status === 'NO_SHOW' && (
              <div className={styles.noShowAlert}>
                <h3>Order Marked as No-Show</h3>
                <p>
                  This order was marked as no-show at {formatTimestamp(order.noShowAt)}. If you believe this is an error or need your order prepared, please contact our team.
                </p>
              </div>
            )}

            <div className={styles.trackerFlow}>
              {progressSteps.map((step, idx) => {
                const isCompleted = isPickedUp ? true : idx < currentStepIndex;
                const isActive = !isPickedUp && idx === currentStepIndex && order.status !== 'NO_SHOW';
                const isFirst = idx === 0;
                const isLast = idx === progressSteps.length - 1;

                return (
                  <div key={step.title} className={styles.trackerStep}>
                    <div className={styles.trackerMarkerWrap}>
                      {!isFirst && (
                        <span
                          className={`${styles.trackerLineLeft} ${idx <= currentStepIndex || isPickedUp ? styles.lineDone : ''}`}
                          aria-hidden
                        />
                      )}
                      {!isLast && (
                        <span
                          className={`${styles.trackerLineRight} ${idx < currentStepIndex || isPickedUp ? styles.lineDone : ''}`}
                          aria-hidden
                        />
                      )}
                      <span
                        className={[
                          styles.trackerMarker,
                          isCompleted ? styles.markerCompleted : '',
                          isActive ? styles.markerActive : '',
                        ].filter(Boolean).join(' ')}
                        aria-hidden
                      >
                        {isCompleted ? <Check size={18} /> : idx + 1}
                      </span>
                    </div>
                    <div>
                      <div
                        className={[
                          styles.stepTitle,
                          isActive ? styles.stepTitleActive : '',
                          isCompleted ? styles.stepTitleDone : '',
                        ].filter(Boolean).join(' ')}
                      >
                        {step.title}
                      </div>
                      <div className={`${styles.stepTime} ${isActive ? styles.stepTimeActive : ''}`}>
                        {step.time ? formatTimestamp(step.time) : (isActive ? 'In progress' : 'Waiting')}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            <button
              type="button"
              className={styles.timelineToggle}
              onClick={() => setTimelineOpen(open => !open)}
              aria-expanded={timelineOpen}
            >
              <span><Clock3 size={16} /> Activity History</span>
              <ChevronDown className={timelineOpen ? styles.chevronOpen : ''} size={16} />
            </button>
            {timelineOpen && (
              <div className={styles.timelineDetails}>
                {timeline.map(step => (
                  <div key={step.label} className={styles.timelineDetailRow}>
                    <strong>{step.label}</strong>
                    <span>{step.value ? formatTimestamp(step.value) : '—'}</span>
                  </div>
                ))}
                {order.status === 'NO_SHOW' && (
                  <div className={styles.timelineDetailRow}>
                    <strong style={{ color: '#ff8585' }}>Marked as no-show</strong>
                    <span>{formatTimestamp(order.noShowAt)}</span>
                  </div>
                )}
              </div>
            )}
          </section>
        </div>

        {/* Secondary / Sidebar Column */}
        <div className={styles.sideColumn}>
          {/* Pickup Details Card */}
          <section className={styles.card} aria-label="Pickup Details">
            <div className={styles.cardHeader}>
              <h2><ShoppingBag size={18} /> Pickup Details</h2>
            </div>

            <div className={styles.sidebarDetailsList}>
              <div className={styles.sidebarDetailItem}>
                <div className={styles.sidebarIconWrap}>
                  <Calendar size={18} />
                </div>
                <div className={styles.sidebarDetailText}>
                  <span>Pickup Date</span>
                  <strong>{isPickedUp ? formatPickedUpDateLabel(order.pickupDate) : formatPickupDateLabel(order.pickupDate)}</strong>
                </div>
              </div>

              <div className={styles.sidebarDetailItem}>
                <div className={styles.sidebarIconWrap}>
                  <Clock3 size={18} />
                </div>
                <div className={styles.sidebarDetailText}>
                  <span>Pickup Time</span>
                  <strong>{isPickedUp ? (pickedUpTimeLabel || 'Completed') : formatPickupTimeLabel(order.pickupTime)}</strong>
                  {isAsap && !isPickedUp && <p>Estimated prep time ~{order.averageReadyMinutes} mins</p>}
                </div>
              </div>

              <div className={styles.sidebarDetailItem}>
                <div className={styles.sidebarIconWrap}>
                  <MapPin size={18} />
                </div>
                <div className={styles.sidebarDetailText}>
                  <span>Location & Instructions</span>
                  <strong>Lord Gainsborough Restaurant</strong>
                  <p>1800 Hyde Park Rd, London, ON N6H 0K1</p>
                  <p style={{ marginTop: '0.45rem', color: 'var(--secondary)' }}>
                    Pick up at counter. Show order number <strong>#{formatOrderId(order.id)}</strong> to staff.
                  </p>
                </div>
              </div>
            </div>
          </section>

          {/* Actions & Support Card */}
          <section className={styles.card} aria-label="Order Actions">
            <div className={styles.cardHeader}>
              <h2><LifeBuoy size={18} /> Actions & Support</h2>
            </div>

            <div className={styles.actionList}>
              {order.status === 'PICKED_UP' && (
                <>
                  <button
                    type="button"
                    className={`${styles.actionBtn} ${styles.actionBtnPrimary}`}
                    onClick={reorderItems}
                    disabled={reordering}
                  >
                    <RotateCcw size={17} /> {reordering ? 'Refreshing Menu...' : 'Reorder These Items'}
                  </button>
                  {!order.reviewed ? (
                    <button
                      type="button"
                      className={styles.actionBtn}
                      onClick={() => initializeReview(order)}
                    >
                      <Star size={17} /> Leave a Review
                    </button>
                  ) : (
                    <div className={styles.reviewedBadge}>
                      <Star size={16} fill="currentColor" /> You reviewed this order
                    </div>
                  )}
                </>
              )}

              <button
                type="button"
                className={styles.actionBtn}
                onClick={() => setHelpOpen(true)}
              >
                <LifeBuoy size={17} /> Need Help with Order?
              </button>
            </div>
          </section>
        </div>
      </div>

      {/* Review Modal */}
      {reviewOpen && (
        <div className={styles.modalBackdrop} onClick={() => setReviewOpen(false)}>
          <form className={styles.modal} onSubmit={submitReview} onClick={event => event.stopPropagation()}>
            <div className={styles.modalHeader}>
              <div>
                <p className={styles.modalEyebrow}>Your Feedback</p>
                <h2>Review Items</h2>
              </div>
              <button
                type="button"
                className={styles.closeButton}
                onClick={() => setReviewOpen(false)}
                aria-label="Close review"
              >
                <X size={18} />
              </button>
            </div>

            <div className={styles.reviewList}>
              {order.items.map(item => (
                <div className={styles.reviewItem} key={item.menuItemId}>
                  <div className={styles.reviewItemTitle}>
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
                    placeholder="What did you think of this dish? (Optional)"
                    rows={2}
                  />
                </div>
              ))}
            </div>

            <button
              className={styles.modalSubmit}
              type="submit"
              disabled={submittingReview || !hasReviewInput}
            >
              {submittingReview ? 'Publishing...' : 'Publish review'}
            </button>
          </form>
        </div>
      )}

      {/* Help Modal */}
      {helpOpen && (
        <div className={styles.modalBackdrop} onClick={() => setHelpOpen(false)}>
          <div className={styles.modal} onClick={event => event.stopPropagation()}>
            <div className={styles.modalHeader}>
              <div>
                <p className={styles.modalEyebrow}>Need assistance?</p>
                <h2>Contact the Restaurant</h2>
              </div>
              <button
                type="button"
                className={styles.closeButton}
                onClick={() => setHelpOpen(false)}
                aria-label="Close help"
              >
                <X size={18} />
              </button>
            </div>

            <div className={styles.helpBody}>
              <p>
                Have questions or need modifications for order <strong>#{formatOrderId(order.id)}</strong>? Give our team a call directly and we will be delighted to assist you.
              </p>
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
