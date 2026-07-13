'use client';

import React, { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useCart } from '@/context/CartContext';
import { useRouter } from 'next/navigation';
import { backendAssetUrl, fetchStoreSettings } from '@/lib/orders';
import styles from './CartSidebar.module.css';
import { X, Plus, Minus, Trash2, ShoppingBag } from 'lucide-react';

const CartSidebar = () => {
  const { cart, isCartOpen, toggleCart, removeFromCart, updateQuantity, cartTotal } = useCart();
  const router = useRouter();
  const [pickupOrdersEnabled, setPickupOrdersEnabled] = useState(true);

  useEffect(() => {
    if (!isCartOpen) {
      return;
    }
    fetchStoreSettings().then(settings => {
      setPickupOrdersEnabled(settings.pickupOrdersEnabled);
    });
  }, [isCartOpen]);

  return (
    <AnimatePresence>
      {isCartOpen && (
        <>
          <motion.div 
            className={styles.backdrop}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={toggleCart}
          />
          <motion.div 
            className={styles.sidebar}
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', bounce: 0, duration: 0.4 }}
          >
            <div className={styles.header}>
              <h2>Your Order</h2>
              <button className={styles.closeBtn} onClick={toggleCart}>
                <X size={24} />
              </button>
            </div>

            <div className={styles.itemsContainer}>
              {cart.length === 0 ? (
                <div className={styles.emptyState}>
                  <ShoppingBag size={48} className={styles.emptyIcon} />
                  <p>Your cart is empty.</p>
                  <button className={styles.continueBtn} onClick={toggleCart}>
                    Continue Exploring
                  </button>
                </div>
              ) : (
                <div className={styles.itemList}>
                  <AnimatePresence mode="popLayout">
                    {cart.map((item) => (
                      <motion.div 
                        key={item.id}
                        className={styles.cartItem}
                        layout
                        initial={{ opacity: 0, scale: 0.8 }}
                        animate={{ opacity: 1, scale: 1 }}
                        exit={{ opacity: 0, scale: 0.8, transition: { duration: 0.2 } }}
                      >
                        <div className={styles.itemImageContainer}>
                          {item.image && item.image !== "" ? (
                            // eslint-disable-next-line @next/next/no-img-element
                            <img src={backendAssetUrl(item.image)} alt={item.name} className={styles.itemImage} />
                          ) : (
                            <div className={styles.noImage}>🍽️</div>
                          )}
                        </div>
                        <div className={styles.itemDetails}>
                          <h4>{item.name}</h4>
                          <span className={styles.itemPrice}>${item.price.toFixed(2)}</span>
                          
                          <div className={styles.quantityControls}>
                            <button onClick={() => item.quantity > 1 ? updateQuantity(item.id, -1) : removeFromCart(item.id)}>
                              {item.quantity === 1 ? <Trash2 size={16} /> : <Minus size={16} />}
                            </button>
                            <span className={styles.quantity}>{item.quantity}</span>
                            <button onClick={() => updateQuantity(item.id, 1)}>
                              <Plus size={16} />
                            </button>
                          </div>
                        </div>
                        <div className={styles.itemTotal}>
                          ${(item.price * item.quantity).toFixed(2)}
                        </div>
                      </motion.div>
                    ))}
                  </AnimatePresence>
                </div>
              )}
            </div>

            {cart.length > 0 && (
              <div className={styles.footer}>
                <div className={styles.totalRow}>
                  <span>Subtotal</span>
                  <span>${cartTotal.toFixed(2)}</span>
                </div>
                <button
                  className={styles.checkoutBtn}
                  disabled={!pickupOrdersEnabled}
                  onClick={() => {
                    if (!pickupOrdersEnabled) {
                      return;
                    }
                    toggleCart();
                    router.push('/checkout');
                  }}
                >
                  {pickupOrdersEnabled ? 'Proceed to Checkout' : 'Pickup orders paused'}
                </button>
              </div>
            )}
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
};

export default CartSidebar;
