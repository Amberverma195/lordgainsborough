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
import { getCartStorageKey, getPickupStorageKey, LEGACY_CART_KEY, LEGACY_PICKUP_KEY } from './storage';
import { CartLine } from './types';

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
  const accountKey = status === 'signedIn' && user?.email ? user.email.trim().toLowerCase() : null;
  const authToken = status === 'signedIn' && user?.token ? user.token : null;

  const [lines, setLines] = useState<CartLine[]>([]);
  const [ready, setReady] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pickupDate, setPickupDate] = useState('');
  const [pickupTime, setPickupTime] = useState('');
  const [serverSync, setServerSync] = useState(0);

  const syncedTokenRef = useRef<string | null>(null);
  const currentAccountRef = useRef<string | null>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const linesRef = useRef(lines);
  useLayoutEffect(() => {
    linesRef.current = lines;
  }, [lines]);

  // Purge unisolated legacy device keys
  useEffect(() => {
    void AsyncStorage.multiRemove([LEGACY_CART_KEY, LEGACY_PICKUP_KEY]).catch(() => undefined);
  }, []);

  // Account switch & initialization effect
  useEffect(() => {
    let cancelled = false;
    currentAccountRef.current = accountKey;
    syncedTokenRef.current = null;
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }

    setReady(false);

    void (async () => {
      try {
        const cartKey = getCartStorageKey(accountKey);
        const pickupKey = getPickupStorageKey(accountKey);
        const [cartRaw, pickupRaw] = await Promise.all([
          AsyncStorage.getItem(cartKey),
          AsyncStorage.getItem(pickupKey),
        ]);

        if (cancelled) {
          return;
        }

        let localLines: CartLine[] = [];
        if (cartRaw) {
          try {
            localLines = parseStoredCart(JSON.parse(cartRaw) as unknown);
          } catch {
            localLines = [];
          }
        }

        let initialPickupDate = '';
        let initialPickupTime = '';
        if (pickupRaw) {
          try {
            const saved = JSON.parse(pickupRaw) as { date?: string; time?: string };
            const date = typeof saved.date === 'string' ? saved.date : '';
            const time = typeof saved.time === 'string' ? saved.time : '';
            if (date && !describePickupIssue(date, time || 'asap')) {
              initialPickupDate = date;
              initialPickupTime = time;
            } else if (date && !describePickupIssue(date, '11:00 AM')) {
              initialPickupDate = date;
            }
          } catch {
            // Keep empty
          }
        }

        if (authToken) {
          try {
            const remote = await fetchServerCart(authToken);
            if (cancelled) {
              return;
            }
            const remoteLines = fromServer(remote);
            // Reconcile server cart with local lines saved specifically for this account
            const resolved = localLines.length > 0 ? mergeCarts(localLines, remoteLines) : remoteLines;
            linesRef.current = resolved;
            setLines(resolved);
            setPickupDate(initialPickupDate);
            setPickupTime(initialPickupTime);
            syncedTokenRef.current = authToken;
            setServerSync((val) => val + 1);
          } catch {
            if (cancelled) {
              return;
            }
            linesRef.current = localLines;
            setLines(localLines);
            setPickupDate(initialPickupDate);
            setPickupTime(initialPickupTime);
            syncedTokenRef.current = authToken;
          }
        } else {
          linesRef.current = localLines;
          setLines(localLines);
          setPickupDate(initialPickupDate);
          setPickupTime(initialPickupTime);
        }
      } catch {
        if (!cancelled) {
          linesRef.current = [];
          setLines([]);
          setPickupDate('');
          setPickupTime('');
        }
      } finally {
        if (!cancelled) {
          setReady(true);
        }
      }
    })();

    return () => {
      cancelled = true;
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
        debounceTimerRef.current = null;
      }
    };
  }, [accountKey, authToken]);

  // Persist to account-scoped AsyncStorage whenever lines or pickup change
  useEffect(() => {
    if (!ready) {
      return;
    }
    const cartKey = getCartStorageKey(currentAccountRef.current);
    void AsyncStorage.setItem(cartKey, JSON.stringify(lines));
  }, [lines, ready]);

  useEffect(() => {
    if (!ready) {
      return;
    }
    const pickupKey = getPickupStorageKey(currentAccountRef.current);
    void AsyncStorage.setItem(pickupKey, JSON.stringify({ date: pickupDate, time: pickupTime }));
  }, [pickupDate, pickupTime, ready]);

  // Debounced server save
  useEffect(() => {
    if (!ready || !authToken || syncedTokenRef.current !== authToken) {
      return;
    }
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = setTimeout(() => {
      debounceTimerRef.current = null;
      void saveServerCart(authToken, toServer(linesRef.current)).catch(() => undefined);
    }, 800);

    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
        debounceTimerRef.current = null;
      }
    };
  }, [lines, ready, serverSync, authToken]);

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
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    linesRef.current = [];
    setLines([]);
    setPickupDate('');
    setPickupTime('');
    const cartKey = getCartStorageKey(currentAccountRef.current);
    const pickupKey = getPickupStorageKey(currentAccountRef.current);
    void AsyncStorage.multiRemove([cartKey, pickupKey]).catch(() => undefined);
    if (authToken) {
      void saveServerCart(authToken, []).catch(() => undefined);
    }
  }, [authToken]);

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
