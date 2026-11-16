import AsyncStorage from '@react-native-async-storage/async-storage';
import React, { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

import { fetchServerCart, saveServerCart, ServerCartItem } from '../api/orders';
import { MenuItem } from '../api/parse';
import { useAuth } from '../auth/AuthContext';
import { describePickupIssue, isPickupDateValid, isPickupTimeValid } from '../checkout/pickup';
import {
  addToCart,
  mergeCarts,
  parseStoredCart,
  reconcileWithCatalog,
  removeFromCart,
  setQuantity,
} from './operations';
import { estimateTotals } from './money';
import { CartLine } from './types';

const CART_KEY = 'lgb.cart.v1';
const PICKUP_KEY = 'lgb.pickup.v1';

interface CartContextValue {
  ready: boolean;
  lines: CartLine[];
  count: number;
  notice: string | null;
  pickupDate: string;
  pickupTime: string;
  addItem: (item: MenuItem, quantity?: number) => string | null;
  updateQuantity: (menuItemId: string, quantity: number) => string | null;
  removeItem: (menuItemId: string) => void;
  clear: () => void;
  reconcile: (catalog: MenuItem[]) => void;
  setPickup: (date: string, time: string) => void;
  dismissNotice: () => void;
  estimate: (taxRate: number) => { subtotal: number; tax: number; total: number };
}

const CartContext = createContext<CartContextValue | undefined>(undefined);

function fromServer(items: ServerCartItem[]): CartLine[] {
  return items
    .filter((item) => item.menuItemId && item.name && item.quantity > 0 && item.price >= 0)
    .map((item) => ({
      menuItemId: item.menuItemId,
      name: item.name,
      description: item.description,
      unitPrice: item.price,
      category: item.category,
      image: item.image,
      quantity: item.quantity,
      available: true,
    }));
}

function toServer(lines: CartLine[]): ServerCartItem[] {
  return lines.map((line) => ({
    menuItemId: line.menuItemId,
    name: line.name,
    description: line.description,
    price: line.unitPrice,
    category: line.category,
    image: line.image,
    quantity: line.quantity,
  }));
}

export function CartProvider({ children }: { children: React.ReactNode }) {
  const { user, status } = useAuth();
  const [lines, setLines] = useState<CartLine[]>([]);
  const [ready, setReady] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pickupDate, setPickupDate] = useState('');
  const [pickupTime, setPickupTime] = useState('');
  const [serverSync, setServerSync] = useState(0);
  const syncedTokenRef = useRef<string | null>(null);
  const linesRef = useRef(lines);
  useLayoutEffect(() => {
    linesRef.current = lines;
  }, [lines]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [cartRaw, pickupRaw] = await Promise.all([
          AsyncStorage.getItem(CART_KEY),
          AsyncStorage.getItem(PICKUP_KEY),
        ]);
        if (cancelled) {
          return;
        }
        if (cartRaw) {
          setLines(parseStoredCart(JSON.parse(cartRaw) as unknown));
        }
        if (pickupRaw) {
          const saved = JSON.parse(pickupRaw) as { date?: string; time?: string };
          const date = typeof saved.date === 'string' ? saved.date : '';
          const time = typeof saved.time === 'string' ? saved.time : '';
          if (date && !describePickupIssue(date, time || 'asap')) {
            setPickupDate(date);
            setPickupTime(time);
          } else if (date && !describePickupIssue(date, '11:00 AM')) {
            setPickupDate(date);
          }
        }
      } catch {
        if (!cancelled) {
          setLines([]);
        }
      } finally {
        if (!cancelled) {
          setReady(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!ready) {
      return;
    }
    void AsyncStorage.setItem(CART_KEY, JSON.stringify(lines));
  }, [lines, ready]);

  useEffect(() => {
    if (!ready) {
      return;
    }
    void AsyncStorage.setItem(PICKUP_KEY, JSON.stringify({ date: pickupDate, time: pickupTime }));
  }, [pickupDate, pickupTime, ready]);

  useEffect(() => {
    if (!ready || status !== 'signedIn' || !user) {
      syncedTokenRef.current = null;
      return;
    }
    const token = user.token;
    syncedTokenRef.current = null;
    let cancelled = false;
    void (async () => {
      try {
        const remote = await fetchServerCart(token);
        if (cancelled) {
          return;
        }
        setLines((current) => {
          const merged = mergeCarts(current, fromServer(remote));
          linesRef.current = merged;
          return merged;
        });
      } catch {
        // The device cart remains usable when the account cart cannot be read.
      } finally {
        if (!cancelled) {
          syncedTokenRef.current = token;
          setServerSync((value) => value + 1);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ready, status, user]);

  useEffect(() => {
    if (!ready || !user || syncedTokenRef.current !== user.token) {
      return;
    }
    const token = user.token;
    const timer = setTimeout(() => {
      void saveServerCart(token, toServer(linesRef.current)).catch(() => undefined);
    }, 800);
    return () => clearTimeout(timer);
  }, [lines, ready, serverSync, user]);

  const addItem = useCallback((item: MenuItem, quantity = 1) => {
    const result = addToCart(linesRef.current, item, quantity);
    linesRef.current = result.cart;
    setLines(result.cart);
    setNotice(result.error);
    return result.error;
  }, []);

  const updateQuantity = useCallback((menuItemId: string, quantity: number) => {
    const result = setQuantity(linesRef.current, menuItemId, quantity);
    linesRef.current = result.cart;
    setLines(result.cart);
    setNotice(result.error);
    return result.error;
  }, []);

  const removeItem = useCallback((menuItemId: string) => {
    const next = removeFromCart(linesRef.current, menuItemId);
    linesRef.current = next;
    setLines(next);
  }, []);

  const clear = useCallback(() => {
    linesRef.current = [];
    setLines([]);
    setPickupDate('');
    setPickupTime('');
  }, []);

  const reconcile = useCallback((catalog: MenuItem[]) => {
    setLines((current) => {
      const next = reconcileWithCatalog(current, catalog);
      linesRef.current = next;
      return next;
    });
  }, []);

  const setPickup = useCallback((date: string, time: string) => {
    if (!isPickupDateValid(date)) {
      setNotice('Choose a pickup date within the next 90 days.');
      return;
    }
    if (time && !isPickupTimeValid(date, time)) {
      setPickupDate(date);
      setPickupTime('');
      setNotice('That pickup time is no longer available.');
      return;
    }
    setPickupDate(date);
    setPickupTime(time);
    setNotice(null);
  }, []);

  const value = useMemo<CartContextValue>(() => ({
    ready,
    lines,
    count: lines.reduce((sum, line) => sum + line.quantity, 0),
    notice,
    pickupDate,
    pickupTime,
    addItem,
    updateQuantity,
    removeItem,
    clear,
    reconcile,
    setPickup,
    dismissNotice: () => setNotice(null),
    estimate: (taxRate: number) => estimateTotals(lines, taxRate),
  }), [
    ready,
    lines,
    notice,
    pickupDate,
    pickupTime,
    addItem,
    updateQuantity,
    removeItem,
    clear,
    reconcile,
    setPickup,
  ]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) {
    throw new Error('useCart must be used within CartProvider');
  }
  return value;
}
