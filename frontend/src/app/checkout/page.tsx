'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { useCart } from '@/context/CartContext';
import styles from './checkout.module.css';
import { CreditCard, Clock, CalendarDays, NotebookPen, ChevronDown } from 'lucide-react';
import {
  API_BASE,
  StoreSettings,
  fetchStoreSettings,
  formatPickupDateLabel,
  getTodayString,
  storeTrackingToken,
  getOrCreateCheckoutIdempotencyKey,
  clearCheckoutIdempotencyKey,
  ORDER_IDEMPOTENCY_KEY_HEADER,
} from '@/lib/orders';
import { formatPhoneInput, getPhoneDigitCount } from '@/lib/phone';
import {
  isAuthenticated,
  isPickupDisabledForUser,
  PICKUP_DISABLED_NO_SHOW_MESSAGE,
  type AuthData,
} from '@/lib/auth';
import LoginModal from '@/components/LoginModal';

function generateTimeSlots(): string[] {
  const slots: string[] = [];
  for (let hour = 11; hour <= 21; hour++) {
    for (const minute of [0, 30]) {
      if (hour === 21 && minute === 30) break;
      const displayHour = hour > 12 ? hour - 12 : hour;
      const ampm = hour >= 12 ? 'PM' : 'AM';
      const minStr = String(minute).padStart(2, '0');
      slots.push(`${displayHour}:${minStr} ${ampm}`);
    }
  }
  return slots;
}

function profileFromAuth(data: AuthData) {
  const nameParts = (data.name || '').trim().split(/\s+/);
  const firstName = nameParts[0] || '';
  const lastName = nameParts.slice(1).join(' ') || '';
  return {
    firstName,
    lastName,
    email: data.email || '',
    phone: data.phone ? formatPhoneInput(data.phone) : '',
  };
}

function profileFromStorage() {
  const name = localStorage.getItem('userName') || '';
  const email = localStorage.getItem('userEmail') || '';
  const phone = localStorage.getItem('userPhone') || '';
  return profileFromAuth({ name, email, phone });
}

export default function CheckoutPage() {
  const router = useRouter();
  const { cart, cartTotal, clearCart } = useCart();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [storeSettings, setStoreSettings] = useState<StoreSettings | null>(null);
  const [pickupDisabledForUser, setPickupDisabledForUser] = useState(false);
  const pendingOrderSubmit = useRef(false);
  const submittingRef = useRef(false);
  const [pickupTimeOpen, setPickupTimeOpen] = useState(false);
  const [form, setForm] = useState({
    pickupDate: getTodayString(),
    pickupTime: 'asap',
    specialInstructions: '',
  });

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      const savedDate = sessionStorage.getItem('pickupDate');
      const savedTime = sessionStorage.getItem('pickupTime');
      setForm(prev => ({
        ...prev,
        pickupDate: savedDate || prev.pickupDate,
        pickupTime: savedTime || prev.pickupTime,
      }));
    }, 0);

    return () => window.clearTimeout(timeout);
  }, []);

  useEffect(() => {
    fetchStoreSettings().then(setStoreSettings);
    const timeout = window.setTimeout(() => {
      setPickupDisabledForUser(isPickupDisabledForUser());
    }, 0);
    return () => window.clearTimeout(timeout);
  }, []);

  type CheckoutForm = typeof form;

  const placeOrder = useCallback(async (formData: CheckoutForm = form) => {
    if (submittingRef.current) {
      return;
    }

    submittingRef.current = true;
    setIsSubmitting(true);
    setError('');
    let completed = false;

    try {
      const settings = storeSettings ?? await fetchStoreSettings();
      if (!settings.pickupOrdersEnabled) {
        setError('Pickup orders are currently paused. Please check back later.');
        return;
      }

      if (isPickupDisabledForUser()) {
        setPickupDisabledForUser(true);
        setError(PICKUP_DISABLED_NO_SHOW_MESSAGE);
        return;
      }

      const profile = profileFromStorage();
      if (!profile.firstName || !profile.email) {
        setError('Your account is missing contact details. Please update your profile and try again.');
        return;
      }

      if (getPhoneDigitCount(profile.phone) !== 10) {
        setError('Your account needs a valid 10-digit phone number. Please update your profile and try again.');
        return;
      }

      const idempotencyKey = getOrCreateCheckoutIdempotencyKey(cart);
      const tax = cartTotal * settings.taxRate;
      const payload = {
        customerName: `${profile.firstName.trim()} ${profile.lastName.trim()}`.trim(),
        customerEmail: profile.email.trim(),
        customerPhone: profile.phone.trim(),
        pickupDate: formData.pickupDate,
        pickupTime: formData.pickupTime,
        specialInstructions: formData.specialInstructions.trim(),
        averageReadyMinutes: settings.defaultReadyMinutes,
        idempotencyKey,
        items: cart.map(item => ({
          menuItemId: item.id,
          name: item.name,
          quantity: item.quantity,
          unitPrice: item.price,
          total: item.price * item.quantity,
        })),
        subtotal: cartTotal,
        tax,
        total: cartTotal + tax,
      };

      const res = await fetch(`${API_BASE}/orders`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          [ORDER_IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
        },
        body: JSON.stringify(payload),
      });
      const data = await res.json().catch(() => null);

      if (res.status === 503) {
        setError(typeof data?.message === 'string'
          ? data.message
          : 'Pickup orders are currently paused. Please check back later.');
        return;
      }

      if (res.status === 403) {
        setPickupDisabledForUser(true);
        setError(typeof data?.message === 'string' ? data.message : PICKUP_DISABLED_NO_SHOW_MESSAGE);
        return;
      }

      if (res.status === 409) {
        clearCheckoutIdempotencyKey();
        setError(typeof data?.message === 'string'
          ? data.message
          : 'An order conflict occurred. Please review your cart and try again.');
        return;
      }

      if (!res.ok) {
        setError(typeof data?.message === 'string'
          ? data.message
          : 'The restaurant could not accept this order. Please review your cart and try again.');
        return;
      }

      const order = data?.order ?? data;
      if (!order?.id) {
        throw new Error('The order was created without a valid confirmation.');
      }

      if (data.trackingToken && order?.id) {
        storeTrackingToken(order.id, data.trackingToken);
      }

      clearCheckoutIdempotencyKey();
      clearCart();
      sessionStorage.removeItem('pickupDate');
      sessionStorage.removeItem('pickupTime');
      completed = true;
      router.replace(`/orders/${encodeURIComponent(order.id)}/confirmation`);
    } catch (requestError) {
      setError(requestError instanceof TypeError
        ? 'We could not reach the restaurant. Check your connection and try again.'
        : 'We could not confirm the order. Please try again.');
    } finally {
      if (!completed) {
        submittingRef.current = false;
        setIsSubmitting(false);
      }
    }
  }, [cart, cartTotal, clearCart, form, router, storeSettings]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!isAuthenticated()) {
      pendingOrderSubmit.current = true;
      setShowLoginModal(true);
      return;
    }

    await placeOrder();
  };

  const handleLoginSuccess = (data: AuthData) => {
    if (data.pickupDisabled) {
      setPickupDisabledForUser(true);
      pendingOrderSubmit.current = false;
      setError(PICKUP_DISABLED_NO_SHOW_MESSAGE);
      return;
    }
    if (pendingOrderSubmit.current) {
      pendingOrderSubmit.current = false;
      void placeOrder();
    }
  };

  if (cart.length === 0) {
    return (
      <div className={styles.container}>
        <div className={styles.emptyState}>
          <h1 className={styles.title}>Checkout</h1>
          <p className={styles.subtitle}>Your cart is currently empty.</p>
          <button className={styles.primaryBtn} onClick={() => router.push('/menu')}>
            Return to Menu
          </button>
        </div>
      </div>
    );
  }

  const taxRate = storeSettings?.taxRate ?? 0.13;
  const tax = cartTotal * taxRate;
  const finalTotal = cartTotal + tax;
  const timeSlots = generateTimeSlots();
  const pickupPaused = storeSettings?.pickupOrdersEnabled === false;
  const pickupBlocked = pickupPaused || pickupDisabledForUser;

  return (
    <div className={styles.container}>
      <LoginModal
        open={showLoginModal}
        onClose={() => {
          pendingOrderSubmit.current = false;
          setShowLoginModal(false);
        }}
        onSuccess={handleLoginSuccess}
        title="Sign in to place your order"
        subtitle="Create an account or sign in to complete checkout and track your order."
      />

      <motion.div
        className={styles.header}
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1 className={styles.title}>Confirm Your Order</h1>
        <p className={styles.subtitle}>Pick-up only - Pay at Restaurant</p>
      </motion.div>

      <div className={styles.checkoutGrid}>
        <motion.div
          className={styles.formSection}
          initial={{ opacity: 0, x: -20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: 0.1 }}
        >
          <form className={`${styles.card} premium-card`} onSubmit={handleSubmit}>
            <div className={styles.formGroup}>
              <h2 className={styles.sectionTitle}>
                <NotebookPen size={24} className={styles.icon} /> Special Instructions
              </h2>
              <div className={styles.inputField}>
                <label>Allergies, preferences, or notes for the kitchen (optional)</label>
                <textarea
                  className={styles.textareaField}
                  rows={3}
                  maxLength={500}
                  placeholder="e.g. No onions, extra napkins, allergic to peanuts"
                  value={form.specialInstructions}
                  onChange={e => setForm(prev => ({ ...prev, specialInstructions: e.target.value }))}
                />
              </div>
            </div>

            <div className={styles.paymentNote}>
              <CreditCard size={20} className={styles.icon} />
              <p>You will pay for your order <strong>at the restaurant</strong> when you arrive for pick-up. We accept cash, credit, and debit cards on-site.</p>
            </div>

            {pickupDisabledForUser && (
              <p className={styles.errorText}>{PICKUP_DISABLED_NO_SHOW_MESSAGE}</p>
            )}

            {pickupPaused && !pickupDisabledForUser && (
              <p className={styles.errorText}>Pickup orders are currently paused. Please check back later.</p>
            )}

            {error && <p className={styles.errorText}>{error}</p>}

            <button
              type="submit"
              className={styles.submitBtn}
              disabled={isSubmitting || pickupBlocked}
            >
              {pickupDisabledForUser
                ? PICKUP_DISABLED_NO_SHOW_MESSAGE
                : pickupPaused
                  ? 'Pickup orders paused'
                  : isSubmitting
                    ? 'Processing...'
                    : `Confirm Pick-up Order ($${finalTotal.toFixed(2)})`}
            </button>
          </form>
        </motion.div>

        <motion.div
          className={styles.summarySection}
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: 0.2 }}
        >
          <div className={styles.summaryStack}>
            <div className={`${styles.pickupPanel} premium-card`}>
              <button
                type="button"
                className={styles.pickupToggle}
                onClick={() => setPickupTimeOpen(open => !open)}
                aria-expanded={pickupTimeOpen}
              >
                <span className={styles.pickupToggleMain}>
                  <Clock size={16} className={styles.icon} />
                  <span className={styles.pickupToggleTitle}>Pick-up Time</span>
                  <span className={styles.pickupToggleSummary}>
                    {formatPickupDateLabel(form.pickupDate)}
                    {' · '}
                    {form.pickupTime === 'asap' ? 'As soon as possible' : form.pickupTime}
                  </span>
                </span>
                <ChevronDown
                  size={16}
                  className={`${styles.pickupChevron} ${pickupTimeOpen ? styles.pickupChevronOpen : ''}`}
                />
              </button>

              {pickupTimeOpen && (
                <div className={styles.pickupPanelBody}>
                  <div className={styles.pickupFields}>
                    <div className={styles.pickupField}>
                      <label htmlFor="checkout-pickup-date">
                        <CalendarDays size={14} /> Date
                      </label>
                      <input
                        id="checkout-pickup-date"
                        type="date"
                        min={getTodayString()}
                        required
                        value={form.pickupDate}
                        onChange={e => setForm(prev => ({ ...prev, pickupDate: e.target.value }))}
                      />
                    </div>
                    <div className={styles.pickupField}>
                      <label htmlFor="checkout-pickup-time">Requested time</label>
                      <select
                        id="checkout-pickup-time"
                        required
                        className={styles.pickupSelect}
                        value={form.pickupTime}
                        onChange={e => setForm(prev => ({ ...prev, pickupTime: e.target.value }))}
                      >
                        <option value="asap">As soon as possible</option>
                        {timeSlots.map(slot => (
                          <option key={slot} value={slot}>{slot}</option>
                        ))}
                      </select>
                    </div>
                  </div>
                </div>
              )}
            </div>

            <div className={`${styles.summaryCard} premium-card`}>
              <h2 className={styles.sectionTitle}>Order Summary</h2>

            <div className={styles.summaryItems}>
              {cart.map((item) => (
                <div key={item.id} className={styles.summaryItem}>
                  <div className={styles.itemInfo}>
                    <span className={styles.itemQty}>{item.quantity}x</span>
                    <span className={styles.itemName}>{item.name}</span>
                  </div>
                  <span className={styles.itemPrice}>${(item.price * item.quantity).toFixed(2)}</span>
                </div>
              ))}
            </div>

            <div className={styles.summaryTotals}>
              <div className={styles.totalRow}>
                <span>Subtotal</span>
                <span>${cartTotal.toFixed(2)}</span>
              </div>
              <div className={styles.totalRow}>
                <span>Tax ({(taxRate * 100).toFixed(1)}%)</span>
                <span>${tax.toFixed(2)}</span>
              </div>
              <div className={`${styles.totalRow} ${styles.grandTotal}`}>
                <span>Total</span>
                <span>${finalTotal.toFixed(2)}</span>
              </div>
            </div>
          </div>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
