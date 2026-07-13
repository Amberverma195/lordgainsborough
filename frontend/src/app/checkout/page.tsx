'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { useCart } from '@/context/CartContext';
import styles from './checkout.module.css';
import { CreditCard, MapPin, CheckCircle, Clock, CalendarDays, NotebookPen } from 'lucide-react';
import {
  API_BASE,
  CustomerOrder,
  StoreSettings,
  fetchStoreSettings,
  formatOrderId,
  getTodayString,
  storeTrackingToken,
} from '@/lib/orders';
import { formatPhoneInput, getPhoneDigitCount } from '@/lib/phone';
import { isAuthenticated, type AuthData } from '@/lib/auth';
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

function applyAuthToForm(data: AuthData) {
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

function prefillFromStorage() {
  const name = localStorage.getItem('userName') || '';
  const email = localStorage.getItem('userEmail') || '';
  const phone = localStorage.getItem('userPhone') || '';
  return applyAuthToForm({ name, email, phone });
}

export default function CheckoutPage() {
  const router = useRouter();
  const { cart, cartTotal, clearCart } = useCart();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [createdOrder, setCreatedOrder] = useState<CustomerOrder | null>(null);
  const [error, setError] = useState('');
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [storeSettings, setStoreSettings] = useState<StoreSettings | null>(null);
  const pendingOrderSubmit = useRef(false);
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
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
        ...(isAuthenticated() ? prefillFromStorage() : {}),
      }));
    }, 0);

    return () => window.clearTimeout(timeout);
  }, []);

  useEffect(() => {
    fetchStoreSettings().then(setStoreSettings);
  }, []);

  const handlePhoneChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setForm(prev => ({ ...prev, phone: formatPhoneInput(e.target.value) }));
  };

  type CheckoutForm = typeof form;

  const placeOrder = useCallback(async (formData: CheckoutForm = form) => {
    setError('');

    const settings = storeSettings ?? await fetchStoreSettings();
    if (!settings.pickupOrdersEnabled) {
      setError('Pickup orders are currently paused. Please check back later.');
      return;
    }

    if (getPhoneDigitCount(formData.phone) !== 10) {
      setError('Please enter a valid 10-digit phone number.');
      return;
    }

    setIsSubmitting(true);

    const tax = cartTotal * settings.taxRate;
    const payload = {
      customerName: `${formData.firstName.trim()} ${formData.lastName.trim()}`.trim(),
      customerEmail: formData.email.trim(),
      customerPhone: formData.phone.trim(),
      pickupDate: formData.pickupDate,
      pickupTime: formData.pickupTime,
      specialInstructions: formData.specialInstructions.trim(),
      averageReadyMinutes: settings.defaultReadyMinutes,
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

    try {
      const res = await fetch(`${API_BASE}/orders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (res.status === 503) {
        setError('Pickup orders are currently paused. Please check back later.');
        return;
      }

      if (!res.ok) {
        throw new Error('Order could not be placed.');
      }

      const data = await res.json();
      const order: CustomerOrder = data.order ?? data;
      if (data.trackingToken && order?.id) {
        storeTrackingToken(order.id, data.trackingToken);
      }
      setCreatedOrder(order);
      clearCart();
      sessionStorage.removeItem('pickupDate');
      sessionStorage.removeItem('pickupTime');
    } catch {
      setError('We could not place the order. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  }, [cart, cartTotal, clearCart, form, storeSettings]);

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
    const profile = applyAuthToForm(data);
    setForm(prev => {
      const next = { ...prev, ...profile };
      if (pendingOrderSubmit.current) {
        pendingOrderSubmit.current = false;
        void placeOrder(next);
      }
      return next;
    });
  };

  if (createdOrder) {
    return (
      <div className={styles.container}>
        <motion.div
          className={styles.successCard}
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <CheckCircle size={80} className={styles.successIcon} />
          <h1 className={styles.title}>Order Confirmed</h1>
          <p className={styles.subtitle}>Thank you for your order. The kitchen has received it.</p>
          <p className={styles.orderId}>Order ID: <strong>#{formatOrderId(createdOrder.id)}</strong></p>
          <div className={styles.successActions}>
            <button className={styles.primaryBtn} onClick={() => router.push(`/orders/${createdOrder.id}`)}>
              Track Order
            </button>
            <button className={styles.secondaryBtn} onClick={() => router.push('/')}>
              Return Home
            </button>
          </div>
        </motion.div>
      </div>
    );
  }

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
                <MapPin size={24} className={styles.icon} /> Pick-up Information
              </h2>

              <div className={styles.inputRow}>
                <div className={styles.inputField}>
                  <label>First Name</label>
                  <input
                    type="text"
                    required
                    placeholder="Alexander"
                    value={form.firstName}
                    onChange={e => setForm(prev => ({ ...prev, firstName: e.target.value }))}
                  />
                </div>
                <div className={styles.inputField}>
                  <label>Last Name</label>
                  <input
                    type="text"
                    required
                    placeholder="Sterling"
                    value={form.lastName}
                    onChange={e => setForm(prev => ({ ...prev, lastName: e.target.value }))}
                  />
                </div>
              </div>

              <div className={styles.inputField}>
                <label>Email Address</label>
                <input
                  type="email"
                  required
                  placeholder="alexander@example.com"
                  value={form.email}
                  onChange={e => setForm(prev => ({ ...prev, email: e.target.value }))}
                />
              </div>

              <div className={styles.inputField}>
                <label>Phone Number</label>
                <input
                  type="tel"
                  inputMode="numeric"
                  autoComplete="tel"
                  required
                  maxLength={17}
                  placeholder="+1 (555) 000-0000"
                  value={form.phone}
                  onChange={handlePhoneChange}
                />
              </div>
            </div>

            <div className={styles.divider}></div>

            <div className={styles.formGroup}>
              <h2 className={styles.sectionTitle}>
                <Clock size={24} className={styles.icon} /> Pick-up Time
              </h2>
              <div className={styles.inputRow}>
                <div className={styles.inputField}>
                  <label><CalendarDays size={16} /> Date</label>
                  <input
                    type="date"
                    min={getTodayString()}
                    required
                    value={form.pickupDate}
                    onChange={e => setForm(prev => ({ ...prev, pickupDate: e.target.value }))}
                  />
                </div>
                <div className={styles.inputField}>
                  <label>Requested Pick-up Time</label>
                  <select
                    required
                    className={styles.selectField}
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

            <div className={styles.divider}></div>

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

            {pickupPaused && (
              <p className={styles.errorText}>Pickup orders are currently paused. Please check back later.</p>
            )}

            {error && <p className={styles.errorText}>{error}</p>}

            <button
              type="submit"
              className={styles.submitBtn}
              disabled={isSubmitting || pickupPaused}
            >
              {pickupPaused
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

            <div className={styles.deliveryNote}>
              <MapPin size={20} className={styles.icon} />
              <p>Pick-up location: <strong>1800 Hyde Park Rd, London, ON</strong></p>
            </div>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
