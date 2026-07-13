'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { CalendarDays, CheckCircle2, Clock3 } from 'lucide-react';
import { getAuthHeadersOnly } from '@/lib/auth';
import {
  API_BASE,
  CustomerOrder,
  ORDER_TRACKING_TOKEN_HEADER,
  formatOrderId,
  formatPickupTimeLabel,
  getStoredTrackingToken,
} from '@/lib/orders';
import styles from './order-confirmation.module.css';

type OrderConfirmationClientProps = {
  orderId: string;
};

function orderAccessHeaders(orderId: string): HeadersInit {
  const headers = { ...(getAuthHeadersOnly() as Record<string, string>) };
  const trackingToken = getStoredTrackingToken(orderId);
  if (trackingToken) {
    headers[ORDER_TRACKING_TOKEN_HEADER] = trackingToken;
  }
  return headers;
}

function formatPickupDate(value: string): string {
  const [year, month, day] = value.split('-').map(Number);
  if (!year || !month || !day) {
    return value;
  }
  return new Date(year, month - 1, day).toLocaleDateString([], {
    weekday: 'short',
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  });
}

export default function OrderConfirmationClient({ orderId }: OrderConfirmationClientProps) {
  const [order, setOrder] = useState<CustomerOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const loadOrder = async () => {
      try {
        const response = await fetch(`${API_BASE}/orders/${encodeURIComponent(orderId)}`, {
          headers: orderAccessHeaders(orderId),
        });
        if (!response.ok) {
          throw new Error('Order load failed.');
        }
        setOrder(await response.json());
      } catch {
        setError('We could not load this order confirmation.');
      } finally {
        setLoading(false);
      }
    };

    void loadOrder();
  }, [orderId]);

  if (loading) {
    return <main className={styles.container}><p className={styles.loading}>Confirming your order...</p></main>;
  }

  if (!order) {
    return (
      <main className={styles.container}>
        <section className={styles.card}>
          <h1>Order confirmation unavailable</h1>
          <p className={styles.muted}>{error}</p>
          <Link className={styles.secondaryButton} href="/profile">View your orders</Link>
        </section>
      </main>
    );
  }

  const isAsap = order.pickupTime?.trim().toLowerCase() === 'asap';

  return (
    <main className={styles.container}>
      <section className={styles.card} aria-live="polite">
        <CheckCircle2 className={styles.successIcon} size={64} aria-hidden />
        <p className={styles.eyebrow}>Order confirmed</p>
        <h1>Sent to the kitchen</h1>
        <p className={styles.orderId}>#{formatOrderId(order.id)}</p>

        <div className={styles.details}>
          <div>
            <CalendarDays size={20} />
            <span>Pickup date</span>
            <strong>{formatPickupDate(order.pickupDate)}</strong>
          </div>
          <div>
            <Clock3 size={20} />
            <span>Pickup time</span>
            <strong>{formatPickupTimeLabel(order.pickupTime)}</strong>
          </div>
        </div>

        {isAsap && (
          <p className={styles.readyEstimate}>
            Estimated ready time: <strong>about {order.averageReadyMinutes} minutes</strong>
          </p>
        )}

        <Link className={styles.primaryButton} href={`/orders/${encodeURIComponent(order.id)}`}>
          Check Order
        </Link>
      </section>
    </main>
  );
}
