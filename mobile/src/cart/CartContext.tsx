import { onAccountChange } from '../auth/lifecycle';
import AsyncStorage from '@react-native-async-storage/async-storage';
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { AppState, type AppStateStatus } from 'react-native';

import { fetchServerCart, saveServerCart, ServerCartItem } from '../api/orders';
import { ApiError } from '../api/errors';
import { MenuItem } from '../api/parse';
import { useAuth } from '../auth/AuthContext';
import {
  describePickupIssue,
  isPickupDateValid,
  isPickupTimeValid,
} from '../checkout/pickup';
import {
  addToCart,
  mergeGuestCart,
  parseStoredCart,
  reconcileWithCatalog,
  removeFromCart,
  setQuantity,
} from './operations';
import { estimateTotals } from './money';
import {
  getCartPendingStorageKey,
  getCartStorageKey,
  getPickupStorageKey,
  GUEST_CART_KEY,
  LEGACY_CART_KEY,
  LEGACY_GUEST_CART_KEY,
  LEGACY_GUEST_PICKUP_KEY,
  LEGACY_PICKUP_KEY,
} from './storage';
import { CartLine } from './types';

export interface CartContextValue {
  ready: boolean;
  lines: CartLine[];
  count: number;
  notice: string | null;
  syncError?: string | null;
  retrySync?: () => void;
  pickupDate: string;
  pickupTime: string;
  addItem: (item: MenuItem, quantity?: number) => string | null;
  updateQuantity: (menuItemId: string, quantity: number) => string | null;
  removeItem: (menuItemId: string) => void;
  clear: () => void;
  reconcile: (catalog: MenuItem[]) => void;
  setPickup: (date: string, time: string) => void;
  dismissNotice: () => void;
  estimate: (taxRate: number) => {
    subtotal: number;
    tax: number;
    total: number;
  };
}

const CartContext = createContext<CartContextValue | undefined>(undefined);

const MAX_SYNC_RETRIES = 3;

function fromServer(items: ServerCartItem[]): CartLine[] {
  return items
    .filter(
      (item) =>
        item.menuItemId && item.name && item.quantity > 0 && item.price >= 0,
    )
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
  const accountKey =
    status === 'signedIn' && user?.email
      ? user.email.trim().toLowerCase()
      : null;
  const authToken = status === 'signedIn' && user?.token ? user.token : null;

  const [lines, setLines] = useState<CartLine[]>([]);
  const [ready, setReady] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [pickupDate, setPickupDate] = useState('');
  const [pickupTime, setPickupTime] = useState('');
  const [serverSync, setServerSync] = useState(0);

  const syncedTokenRef = useRef<string | null>(null);
  const currentAccountRef = useRef<string | null>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryCountRef = useRef(0);
  const isSyncNoticeRef = useRef(false);
  const linesRef = useRef(lines);
  const cartVersionRef = useRef(0);
  const isSavingRef = useRef(false);
  const queuedSaveRef = useRef(false);
  const saveGenerationRef = useRef(0);
  const hydratedRef = useRef(false);
  useLayoutEffect(() => {
    linesRef.current = lines;
  }, [lines]);

  useEffect(
    () =>
      onAccountChange(() => {
        saveGenerationRef.current += 1;
        hydratedRef.current = false;
        if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
        debounceTimerRef.current = null;
        if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
        retryCountRef.current = 0;
        isSyncNoticeRef.current = false;
        syncedTokenRef.current = null;
        linesRef.current = [];
        cartVersionRef.current = 0;
        isSavingRef.current = false;
        queuedSaveRef.current = false;
        setSyncError(null);
        setReady(false);
        setLines([]);
        setPickupDate('');
        setPickupTime('');
      }),
    [],
  );

  // Purge unisolated legacy device keys
  useEffect(() => {
    void AsyncStorage.multiRemove([
      LEGACY_CART_KEY,
      LEGACY_PICKUP_KEY,
      LEGACY_GUEST_CART_KEY,
      LEGACY_GUEST_PICKUP_KEY,
    ]).catch(() => undefined);
  }, []);

  // Account switch & initialization effect
  useEffect(() => {
    let cancelled = false;
    hydratedRef.current = false;
    saveGenerationRef.current += 1;
    isSavingRef.current = false;
    queuedSaveRef.current = false;
    retryCountRef.current = 0;
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    retryTimerRef.current = null;
    currentAccountRef.current = accountKey;
    syncedTokenRef.current = null;
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }

    void (async () => {
      setReady(false);
      setSyncError(null);
      if (isSyncNoticeRef.current) setNotice(null);
      isSyncNoticeRef.current = false;
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
            const saved = JSON.parse(pickupRaw) as {
              date?: string;
              time?: string;
            };
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
            const guestCartKey = GUEST_CART_KEY;
            const pendingKey = getCartPendingStorageKey(accountKey);
            const [remote, guestCartRaw, pendingRaw] = await Promise.all([
              fetchServerCart(authToken),
              AsyncStorage.getItem(guestCartKey),
              AsyncStorage.getItem(pendingKey),
            ]);
            if (cancelled) {
              return;
            }
            const remoteLines = fromServer(remote);
            let guestLines: CartLine[] = [];
            if (guestCartRaw) {
              try {
                guestLines = parseStoredCart(JSON.parse(guestCartRaw) as unknown);
              } catch {
                guestLines = [];
              }
            }
            const hasPendingEdits = pendingRaw === '1';

            let resolved: CartLine[];
            if (guestLines.length > 0) {
              // User had guest items prior to login: merge guest items into account cart
              resolved = mergeGuestCart(guestLines, hasPendingEdits ? localLines : remoteLines);
              // Persist the pending account snapshot before consuming the guest copy.
              await AsyncStorage.setItem(cartKey, JSON.stringify(resolved));
              await AsyncStorage.setItem(pendingKey, '1');
              if (cancelled) return;
              await AsyncStorage.removeItem(guestCartKey);
              if (cancelled) return;
              setServerSync((val) => val + 1);
            } else if (hasPendingEdits) {
              // Local cart had pending edits before restart: preserve them
              resolved = localLines;
            } else {
              // Server is authoritative; avoids resurrecting stale items deleted on another device,
              // even when the server cart is empty (e.g. intentionally cleared elsewhere).
              resolved = remoteLines;
            }

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
          hydratedRef.current = true;
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
    if (!ready || !hydratedRef.current || (authToken && syncedTokenRef.current !== authToken)) {
      return;
    }
    const cartKey = getCartStorageKey(currentAccountRef.current);
    void AsyncStorage.setItem(cartKey, JSON.stringify(lines));
  }, [lines, ready, authToken]);

  useEffect(() => {
    if (!ready || !hydratedRef.current || (authToken && syncedTokenRef.current !== authToken)) {
      return;
    }
    const pickupKey = getPickupStorageKey(currentAccountRef.current);
    void AsyncStorage.setItem(
      pickupKey,
      JSON.stringify({ date: pickupDate, time: pickupTime }),
    );
  }, [pickupDate, pickupTime, ready, authToken]);

  // Re-verify saved pickup date and time when the app resumes
  useEffect(() => {
    if (!ready) {
      return;
    }
    const subscription = AppState.addEventListener(
      'change',
      (status: AppStateStatus) => {
        if (status === 'active') {
          const now = new Date();
          if (pickupDate && !isPickupDateValid(pickupDate, now)) {
            setPickupDate('');
            setPickupTime('');
            setNotice(
              'Your saved pickup date has passed. Please choose a new date.',
            );
          } else if (
            pickupDate &&
            pickupTime &&
            !isPickupTimeValid(pickupDate, pickupTime, now)
          ) {
            setPickupTime('');
            setNotice(
              'Your saved pickup time is no longer available. Please choose a new time.',
            );
          }
        }
      },
    );

    return () => subscription.remove();
  }, [pickupDate, pickupTime, ready]);

  // Cleanup timers on unmount
  useEffect(() => {
    return () => {
      saveGenerationRef.current += 1;
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
        debounceTimerRef.current = null;
      }
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
    };
  }, []);

  const executeServerSave = useCallback(async function executeServerSave(token: string, account: string) {
    const generation = saveGenerationRef.current;
    const ownsSave = () => generation === saveGenerationRef.current &&
      account === currentAccountRef.current && syncedTokenRef.current === token;
    if (!ownsSave()) return;
    if (isSavingRef.current) {
      // An existing save is in-flight: queue the next save so network calls never overlap
      queuedSaveRef.current = true;
      return;
    }

    isSavingRef.current = true;
    queuedSaveRef.current = false;
    const versionAtStart = cartVersionRef.current;
    const itemsToSave = toServer(linesRef.current);

    try {
      await saveServerCart(token, itemsToSave);
      if (!ownsSave()) return;
      // Save succeeded! Reset retry counter and clear any scheduled retry or sync error
      retryCountRef.current = 0;
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
      setSyncError(null);
      if (isSyncNoticeRef.current) {
        isSyncNoticeRef.current = false;
        setNotice(null);
      }

      // Only remove the pending marker if no new local edits occurred while save was in flight
      if (
        versionAtStart === cartVersionRef.current &&
        account === currentAccountRef.current
      ) {
        void AsyncStorage.removeItem(getCartPendingStorageKey(account)).catch(
          () => undefined,
        );
      }
    } catch (err: unknown) {
      if (!ownsSave()) return;
      // Keep pending marker so unsaved changes can be retried / preserved
      const isAuthError =
        err instanceof ApiError && (err.status === 401 || err.status === 403);

      if (isAuthError) {
        const authMsg = 'Your session has expired. Sign in to sync your cart.';
        isSyncNoticeRef.current = true;
        setNotice(authMsg);
        setSyncError(authMsg);
      } else {
        // Transient error (network or 5xx): inform customer and schedule a controlled retry
        if (
          versionAtStart === cartVersionRef.current &&
          account === currentAccountRef.current
        ) {
          if (retryCountRef.current < MAX_SYNC_RETRIES) {
            retryCountRef.current += 1;
            const delay =
              err instanceof ApiError && err.retryAfterSeconds
                ? err.retryAfterSeconds * 1000
                : Math.min(1000 * Math.pow(2, retryCountRef.current - 1), 8000);

            const retryMsg = 'Unable to sync cart with your account. Retrying...';
            isSyncNoticeRef.current = true;
            setNotice(retryMsg);
            setSyncError(retryMsg);

            if (retryTimerRef.current) {
              clearTimeout(retryTimerRef.current);
            }
            retryTimerRef.current = setTimeout(() => {
              retryTimerRef.current = null;
              if (
                syncedTokenRef.current === token &&
                currentAccountRef.current === account
              ) {
                void executeServerSave(token, account);
              }
            }, delay);
          } else {
            const finalMsg =
              'Cart saved locally, but online sync failed. Check your connection.';
            isSyncNoticeRef.current = true;
            setNotice(finalMsg);
            setSyncError(finalMsg);
          }
        }
      }
    } finally {
      if (ownsSave()) {
        isSavingRef.current = false;
        // Only this generation can release the lock or dispatch its queued save.
        if (
          (queuedSaveRef.current || cartVersionRef.current !== versionAtStart) &&
          syncedTokenRef.current === token &&
          currentAccountRef.current === account
        ) {
          queuedSaveRef.current = false;
          retryCountRef.current = 0;
          if (retryTimerRef.current) {
            clearTimeout(retryTimerRef.current);
            retryTimerRef.current = null;
          }
          void executeServerSave(token, account);
        }
      }
    }
  }, []);

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
      if (currentAccountRef.current) {
        void executeServerSave(authToken, currentAccountRef.current);
      }
    }, 800);

    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
        debounceTimerRef.current = null;
      }
    };
  }, [lines, ready, serverSync, authToken, executeServerSave]);

  const markPending = useCallback(() => {
    if (currentAccountRef.current) {
      void AsyncStorage.setItem(
        getCartPendingStorageKey(currentAccountRef.current),
        '1',
      ).catch(() => undefined);
    }
  }, []);

  const clearSyncStateOnMutation = useCallback(() => {
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
    retryCountRef.current = 0;
    if (isSyncNoticeRef.current) {
      isSyncNoticeRef.current = false;
      setNotice(null);
    }
    setSyncError(null);
  }, []);

  const addItem = useCallback((item: MenuItem, quantity = 1) => {
    clearSyncStateOnMutation();
    const result = addToCart(linesRef.current, item, quantity);
    linesRef.current = result.cart;
    setLines(result.cart);
    setNotice(result.error);
    if (!result.error) {
      cartVersionRef.current += 1;
      markPending();
    }
    return result.error;
  }, [clearSyncStateOnMutation, markPending]);

  const updateQuantity = useCallback((menuItemId: string, quantity: number) => {
    clearSyncStateOnMutation();
    const result = setQuantity(linesRef.current, menuItemId, quantity);
    linesRef.current = result.cart;
    setLines(result.cart);
    setNotice(result.error);
    if (!result.error) {
      cartVersionRef.current += 1;
      markPending();
    }
    return result.error;
  }, [clearSyncStateOnMutation, markPending]);

  const removeItem = useCallback((menuItemId: string) => {
    clearSyncStateOnMutation();
    const next = removeFromCart(linesRef.current, menuItemId);
    linesRef.current = next;
    setLines(next);
    cartVersionRef.current += 1;
    markPending();
  }, [clearSyncStateOnMutation, markPending]);

  const clear = useCallback(() => {
    cartVersionRef.current += 1;
    clearSyncStateOnMutation();
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    queuedSaveRef.current = false;
    linesRef.current = [];
    setLines([]);
    setPickupDate('');
    setPickupTime('');
    const account = currentAccountRef.current;
    const cartKey = getCartStorageKey(account);
    const pickupKey = getPickupStorageKey(account);
    const pendingKey = getCartPendingStorageKey(account);
    void AsyncStorage.multiRemove([cartKey, pickupKey, ...(authToken && account ? [] : [pendingKey])]).catch(() => undefined);
    if (authToken && account) {
      void AsyncStorage.setItem(pendingKey, '1').catch(() => undefined);
      void executeServerSave(authToken, account);
    }
  }, [authToken, clearSyncStateOnMutation, executeServerSave]);

  const reconcile = useCallback((catalog: MenuItem[]) => {
    setLines((current) => {
      const next = reconcileWithCatalog(current, catalog);
      linesRef.current = next;
      return next;
    });
  }, []);

  const setPickup = useCallback((date: string, time: string) => {
    const now = new Date();
    if (!isPickupDateValid(date, now)) {
      setNotice('Choose a pickup date within the next 90 days.');
      return;
    }
    if (time && !isPickupTimeValid(date, time, now)) {
      setPickupDate(date);
      setPickupTime('');
      setNotice('That pickup time is no longer available.');
      return;
    }
    setPickupDate(date);
    setPickupTime(time);
    setNotice(null);
  }, []);

  const dismissNotice = useCallback(() => {
    isSyncNoticeRef.current = false;
    setNotice(null);
  }, []);

  const retrySync = useCallback(() => {
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
    retryCountRef.current = 0;
    if (authToken && currentAccountRef.current) {
      void executeServerSave(authToken, currentAccountRef.current);
    }
  }, [authToken, executeServerSave]);

  const value = useMemo<CartContextValue>(
    () => ({
      ready,
      lines,
      count: lines.reduce((sum, line) => sum + line.quantity, 0),
      notice,
      syncError,
      retrySync,
      pickupDate,
      pickupTime,
      addItem,
      updateQuantity,
      removeItem,
      clear,
      reconcile,
      setPickup,
      dismissNotice,
      estimate: (taxRate: number) => estimateTotals(lines, taxRate),
    }),
    [
      ready,
      lines,
      notice,
      syncError,
      retrySync,
      pickupDate,
      pickupTime,
      addItem,
      updateQuantity,
      removeItem,
      clear,
      reconcile,
      setPickup,
      dismissNotice,
    ],
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) {
    throw new Error('useCart must be used within CartProvider');
  }
  return value;
}
