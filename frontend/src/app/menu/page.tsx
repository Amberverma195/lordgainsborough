'use client';

import React, { useState, useEffect, useMemo } from 'react';
import Image from 'next/image';
import styles from './menu.module.css';
import { motion, AnimatePresence } from 'framer-motion';
import { Calendar, Clock } from 'lucide-react';
import { MenuItem } from '@/lib/mockData';
import { API_BASE, backendAssetUrl, fetchStoreSettings } from '@/lib/orders';
import { isPickupDisabledForUser, PICKUP_DISABLED_NO_SHOW_MESSAGE } from '@/lib/auth';
import { useCart } from '@/context/CartContext';
import MenuSkeleton from '@/components/MenuSkeleton';

function getTodayString(): string {
  const today = new Date();
  const year = today.getFullYear();
  const month = String(today.getMonth() + 1).padStart(2, '0');
  const day = String(today.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

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

export default function MenuPage() {
  const [activeCategory, setActiveCategory] = useState('All');
  const [items, setItems] = useState<MenuItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [menuError, setMenuError] = useState('');
  const [pickupDate, setPickupDate] = useState('');
  const [pickupTime, setPickupTime] = useState('');
  const [pickupOrdersEnabled, setPickupOrdersEnabled] = useState(true);
  const [pickupDisabledForUser, setPickupDisabledForUser] = useState(false);
  const { addToCart } = useCart();

  const timeSlots = useMemo(() => generateTimeSlots(), []);
  const pickupAvailable = pickupOrdersEnabled && !pickupDisabledForUser;
  const pickupReady = pickupAvailable && pickupDate !== '' && pickupTime !== '';

  useEffect(() => {
    sessionStorage.removeItem('pickupDate');
    sessionStorage.removeItem('pickupTime');
  }, []);

  useEffect(() => {
    async function loadMenu() {
      try {
        const res = await fetch(`${API_BASE}/menu`);
        if (!res.ok) {
          throw new Error(`Menu request failed with status ${res.status}`);
        }
        const data = await res.json();
        setItems(data);
        setMenuError('');
      } catch {
        setItems([]);
        setMenuError('Menu is temporarily unavailable. Please refresh the page and try again.');
      } finally {
        setLoading(false);
      }
    }

    loadMenu();

    fetchStoreSettings().then(settings => {
      setPickupOrdersEnabled(settings.pickupOrdersEnabled);
    });
    setPickupDisabledForUser(isPickupDisabledForUser());
  }, []);

  const handlePickupDateChange = (value: string) => {
    setPickupDate(value);
    if (value && pickupTime) {
      sessionStorage.setItem('pickupDate', value);
      sessionStorage.setItem('pickupTime', pickupTime);
    } else {
      sessionStorage.removeItem('pickupDate');
    }
  };

  const handlePickupTimeChange = (value: string) => {
    setPickupTime(value);
    if (pickupDate && value) {
      sessionStorage.setItem('pickupDate', pickupDate);
      sessionStorage.setItem('pickupTime', value);
    } else {
      sessionStorage.removeItem('pickupTime');
    }
  };

  const handleAddToCart = (item: MenuItem) => {
    if (!pickupReady) return;
    addToCart(item);
  };

  const categories = ['All', ...Array.from(new Set(items.map(item => item.category)))];

  const filteredItems = activeCategory === 'All' 
    ? items 
    : items.filter(item => item.category === activeCategory);

  if (loading) {
    return <MenuSkeleton />;
  }

  return (
    <div className={styles.container}>
      <motion.h1 
        className={styles.title}
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        Culinary Selection
      </motion.h1>
      <motion.p 
        className={styles.subtitle}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.2 }}
      >
        A curated journey of flavors, crafted with passion.
      </motion.p>

      <motion.div
        className={`${styles.pickupSection} premium-card`}
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.15 }}
      >
        <h2 className={styles.pickupTitle}>Pickup Details</h2>
        {pickupDisabledForUser ? (
          <p className={styles.pickupHint}>{PICKUP_DISABLED_NO_SHOW_MESSAGE}</p>
        ) : !pickupOrdersEnabled ? (
          <p className={styles.pickupHint}>Pickup orders are currently paused. Please check back later.</p>
        ) : (
          <>
            <p className={styles.pickupSubtitle}>Select when you&apos;d like to pick up your order</p>
            <div className={styles.pickupFields}>
              <div className={styles.pickupField}>
                <label><Calendar size={16} /> Pickup Date</label>
                <input
                  type="date"
                  min={getTodayString()}
                  value={pickupDate}
                  onChange={(e) => handlePickupDateChange(e.target.value)}
                  required
                />
              </div>
              <div className={styles.pickupField}>
                <label><Clock size={16} /> Pickup Time</label>
                <select
                  value={pickupTime}
                  onChange={(e) => handlePickupTimeChange(e.target.value)}
                  required
                >
                  <option value="">Select a time</option>
                  {timeSlots.map((slot) => (
                    <option key={slot} value={slot}>{slot}</option>
                  ))}
                </select>
              </div>
            </div>
            {!pickupReady && (
              <p className={styles.pickupHint}>Please select a pickup date and time before adding items to your cart.</p>
            )}
          </>
        )}
      </motion.div>

      {menuError ? (
        <div className={styles.errorState}>{menuError}</div>
      ) : (
        <>
          <div className={styles.filters}>
            {categories.map((cat, index) => (
              <motion.button
                key={cat}
                className={`${styles.filterBtn} ${activeCategory === cat ? styles.activeFilter : ''}`}
                onClick={() => setActiveCategory(cat)}
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.05 * index }}
              >
                {cat}
              </motion.button>
            ))}
          </div>

          <motion.div 
            className={styles.menuGrid}
            layout
          >
            <AnimatePresence mode='popLayout'>
              {filteredItems.map((item) => (
                <motion.div
                  key={item.id}
                  className={`${styles.menuItem} premium-card`}
                  layout
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.9 }}
                  transition={{ duration: 0.3 }}
                >
                  {item.image && item.image !== "" && (
                    <div className={styles.imageWrapper}>
                      <Image
                        src={backendAssetUrl(item.image)}
                        alt={item.name}
                        fill
                        sizes="(max-width: 768px) 100vw, (max-width: 1200px) 50vw, 33vw"
                        className={styles.itemImage}
                      />
                    </div>
                  )}
                  <div className={styles.itemHeader}>
                    <h3 className={styles.itemName}>{item.name}</h3>
                    <span className={styles.itemPrice}>${item.price.toFixed(2)}</span>
                  </div>
                  {item.description && <p className={styles.itemDesc}>{item.description}</p>}
                  <div className={styles.itemFooter}>
                    <button 
                      className={`${styles.addToCartBtn} ${!pickupReady ? styles.addToCartDisabled : ''}`}
                      onClick={() => handleAddToCart(item)}
                      disabled={!pickupReady}
                    >
                      {!pickupOrdersEnabled
                        ? 'Orders Paused'
                        : pickupDisabledForUser
                          ? 'Pickup Disabled'
                          : pickupReady
                            ? 'Add to Cart'
                            : 'Select Pickup Time'}
                    </button>
                  </div>
                </motion.div>
              ))}
            </AnimatePresence>
          </motion.div>
        </>
      )}
    </div>
  );
}
