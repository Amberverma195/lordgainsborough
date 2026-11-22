'use client';

import { apiFetch } from '@/lib/apiFetch';
import React, { createContext, useContext, useState, useEffect, useRef, useCallback } from 'react';
import { MenuItem } from '@/lib/mockData';
import { API_BASE, clearCheckoutIdempotencyKey } from '@/lib/orders';
import { ACCOUNT_DELETED_KEY, clearDeletedAccountData } from '@/lib/accountDeletion';
import { clearAuth, validateServerSession } from '@/lib/auth';
import {
  CartItem,
  toServerItems,
  fromServerItems,
  mergeCartItems,
  purgeLegacyGlobalCart,
  loadGuestCart,
  saveGuestCart,
  clearGuestCart,
  isGenuineGuestCart,
  loadAccountCart,
  saveAccountCart,
  getAccountCartStorageKey,
  GUEST_CART_STORAGE_KEY,
} from '@/lib/cartStorage';

export type { CartItem };

interface CartContextType {
  cart: CartItem[];
  addToCart: (item: MenuItem) => void;
  replaceCart: (items: CartItem[]) => void;
  removeFromCart: (itemId: string) => void;
  updateQuantity: (itemId: string, delta: number) => void;
  cartCount: number;
  cartTotal: number;
  isCartOpen: boolean;
  toggleCart: () => void;
  clearCart: () => void;
  syncError?: string | null;
  retrySync?: () => void;
}

const AUTH_STORAGE_KEYS = new Set(['sessionUser', 'userRole', 'userName', 'userEmail', 'userPhone']);
const MAX_SYNC_RETRIES = 3;

const CartContext = createContext<CartContextType | undefined>(undefined);

function getSessionKey(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('sessionUser');
}

export type ServerCartFetchResult =
  | { ok: true; items: CartItem[] }
  | { ok: false; status: number; reason: 'unauthorized' | 'server_error' | 'network_error'; error?: unknown };

export const CartProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [cart, setCart] = useState<CartItem[]>([]);
  const [isCartOpen, setIsCartOpen] = useState(false);

  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryCountRef = useRef(0);
  const [syncError, setSyncError] = useState<string | null>(null);
  const isSyncingRef = useRef(false);
  const pendingServerSavesRef = useRef(0);
  const cartRef = useRef<CartItem[]>([]);
  const activeAccountRef = useRef<string | null>(null);
  const syncGenerationRef = useRef(0);
  const isApplyingCrossTabRef = useRef(false);
  const cartVersionRef = useRef(0);
  const isSavingRef = useRef(false);
  const queuedSaveRef = useRef(false);

  // --- Server API calls ---

  const fetchServerCart = useCallback(async (accountEmail: string): Promise<ServerCartFetchResult> => {
    if (!accountEmail || accountEmail !== getSessionKey()) {
      return { ok: false, status: 0, reason: 'network_error' };
    }
    try {
      const res = await apiFetch(`${API_BASE}/cart`, {
        cache: 'no-store',
      });
      if (res.status === 200) {
        const data = await res.json();
        return { ok: true, items: fromServerItems(data.items || []) };
      }
      if (res.status === 401 || res.status === 403) {
        return { ok: false, status: res.status, reason: 'unauthorized' };
      }
      return { ok: false, status: res.status, reason: 'server_error' };
    } catch (error) {
      return { ok: false, status: 0, reason: 'network_error', error };
    }
  }, []);

  const clearPendingServerSave = useCallback(() => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
  }, []);

  const saveServerCart = useCallback(async (items: CartItem[], accountEmail: string) => {
    if (!accountEmail || accountEmail !== getSessionKey()) return;
    if (isSavingRef.current) {
      queuedSaveRef.current = true;
      return;
    }

    isSavingRef.current = true;
    queuedSaveRef.current = false;
    const versionAtStart = cartVersionRef.current;
    pendingServerSavesRef.current += 1;

    try {
      const res = await apiFetch(`${API_BASE}/cart`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(toServerItems(items)),
      });
      if (res.status === 401 || res.status === 403) {
        // Session expired on server while attempting to save
        clearPendingServerSave();
        clearAuth();
        activeAccountRef.current = null;
        cartRef.current = [];
        setCart([]);
        if (typeof window !== 'undefined') {
          window.dispatchEvent(new Event('storage'));
        }
        return;
      }
      if (!res.ok) {
        throw new Error(`Failed to save server cart: HTTP ${res.status}`);
      }
      // Save succeeded! Reset retry counter and clear sync errors
      retryCountRef.current = 0;
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
      setSyncError(null);
    } catch (err) {
      console.error('Failed to save server cart', err);
      const currentAccount = activeAccountRef.current ?? getSessionKey();
      if (currentAccount === accountEmail && versionAtStart === cartVersionRef.current) {
        if (retryCountRef.current < MAX_SYNC_RETRIES) {
          retryCountRef.current += 1;
          const delay = Math.min(1000 * Math.pow(2, retryCountRef.current - 1), 8000);
          setSyncError('Unable to sync cart to your account. Retrying...');
          if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
          retryTimerRef.current = setTimeout(() => {
            retryTimerRef.current = null;
            if ((activeAccountRef.current ?? getSessionKey()) === accountEmail) {
              void saveServerCart(cartRef.current, accountEmail);
            }
          }, delay);
        } else {
          setSyncError('Cart changes saved locally, but online sync failed. Check your connection.');
        }
      }
    } finally {
      pendingServerSavesRef.current = Math.max(0, pendingServerSavesRef.current - 1);
      isSavingRef.current = false;
      const currentAccount = activeAccountRef.current ?? getSessionKey();
      if (
        (queuedSaveRef.current || cartVersionRef.current !== versionAtStart) &&
        currentAccount === accountEmail
      ) {
        queuedSaveRef.current = false;
        retryCountRef.current = 0;
        if (retryTimerRef.current) {
          clearTimeout(retryTimerRef.current);
          retryTimerRef.current = null;
        }
        void saveServerCart(cartRef.current, accountEmail);
      }
    }
  }, [clearPendingServerSave]);

  const saveToServerNow = useCallback((items: CartItem[]) => {
    const sessionKey = activeAccountRef.current ?? getSessionKey();
    if (!sessionKey) return;

    clearPendingServerSave();
    void saveServerCart(items, sessionKey);
  }, [clearPendingServerSave, saveServerCart]);

  // Debounced save — fires 1s after last cart mutation
  const debouncedSaveToServer = useCallback((items: CartItem[]) => {
    const sessionKey = activeAccountRef.current ?? getSessionKey();
    if (!sessionKey) return;

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = setTimeout(() => {
      debounceTimerRef.current = null;
      void saveServerCart(items, sessionKey);
    }, 1000);
  }, [saveServerCart]);

  // Helper for applying UI cart mutation and persisting to the correct namespace
  const persistCartMutation = useCallback((updated: CartItem[], immediate = false) => {
    cartVersionRef.current += 1;
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
    retryCountRef.current = 0;
    setSyncError(null);
    cartRef.current = updated;
    setCart(updated);

    const currentAccount = activeAccountRef.current ?? getSessionKey();
    if (currentAccount) {
      saveAccountCart(currentAccount, updated);
      if (immediate) {
        saveToServerNow(updated);
      } else {
        debouncedSaveToServer(updated);
      }
    } else {
      saveGuestCart(updated);
    }
  }, [debouncedSaveToServer, saveToServerNow]);

  // --- Initialisation: validate server session, load storage, sync cart ---

  useEffect(() => {
    let cancelled = false;

    const init = async () => {
      purgeLegacyGlobalCart();
      const generation = ++syncGenerationRef.current;
      const localSession = getSessionKey();

      let authenticatedUser: string | null = null;
      if (localSession) {
        // Validate actual server session rather than trusting sessionUser blindly
        const sessionCheck = await validateServerSession();
        if (cancelled || generation !== syncGenerationRef.current) return;

        if (sessionCheck.authenticated && sessionCheck.user.email) {
          authenticatedUser = sessionCheck.user.email;
        } else if (sessionCheck.reason === 'unauthorized') {
          // Cookie expired on server
          clearAuth();
          authenticatedUser = null;
        } else {
          // Transient network error or offline: retain local session hint for offline view
          authenticatedUser = localSession;
        }
      }

      activeAccountRef.current = authenticatedUser;

      if (authenticatedUser) {
        isSyncingRef.current = true;
        try {
          const hasGuest = isGenuineGuestCart();
          const guestItems = hasGuest ? loadGuestCart() : [];
          const serverRes = await fetchServerCart(authenticatedUser);

          if (cancelled || generation !== syncGenerationRef.current || authenticatedUser !== getSessionKey()) {
            return;
          }

          if (serverRes.ok) {
            let finalCart: CartItem[];
            if (hasGuest && guestItems.length > 0) {
              finalCart = mergeCartItems(guestItems, serverRes.items, { combineQuantities: true });
              clearGuestCart();
              await saveServerCart(finalCart, authenticatedUser);
            } else {
              // Server is authoritative for an authenticated account; an empty server cart
              // must not restore old local items when another device cleared the cart.
              finalCart = serverRes.items;
            }
            cartRef.current = finalCart;
            setCart(finalCart);
            saveAccountCart(authenticatedUser, finalCart);
          } else if (serverRes.reason === 'unauthorized') {
            // Server rejected cart fetch -> session expired
            clearAuth();
            activeAccountRef.current = null;
            cartRef.current = [];
            setCart([]);
          } else {
            // Network error: use account's cached local cart, do NOT treat as empty server cart
            const localAccountCart = loadAccountCart(authenticatedUser);
            cartRef.current = localAccountCart;
            setCart(localAccountCart);
          }
        } finally {
          isSyncingRef.current = false;
        }
      } else {
        // Unauthenticated guest mode: load isolated guest cart
        const guestCart = loadGuestCart();
        cartRef.current = guestCart;
        setCart(guestCart);
      }
    };

    init();
    return () => {
      cancelled = true;
    };
  }, [fetchServerCart, saveServerCart]);

  // --- Session Validation on Window Focus & Visibility Change ---

  useEffect(() => {
    const handleFocus = async () => {
      const currentSession = getSessionKey();
      if (currentSession) {
        const sessionCheck = await validateServerSession();
        if (sessionCheck.reason === 'unauthorized') {
          // Session expired while user was inactive or tab was backgrounded
          syncGenerationRef.current += 1;
          clearPendingServerSave();
          clearAuth();
          activeAccountRef.current = null;
          cartRef.current = [];
          setCart([]);
          if (typeof window !== 'undefined') {
            window.dispatchEvent(new Event('storage'));
          }
        }
      }
    };

    window.addEventListener('focus', handleFocus);
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') {
        void handleFocus();
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [clearPendingServerSave]);

  // --- Listen for storage events (login/logout, account switch, cross-tab updates) ---

  useEffect(() => {
    const handleStorageChange = async (event: StorageEvent | Event) => {
      const storageKey = 'key' in event ? event.key : null;

      if (storageKey === ACCOUNT_DELETED_KEY) {
        syncGenerationRef.current += 1;
        clearPendingServerSave();
        cartRef.current = [];
        setCart([]);
        setIsCartOpen(false);
        clearDeletedAccountData(false);
        window.location.replace('/login?accountDeleted=1');
        return;
      }

      const sessionKey = getSessionKey();
      const prevAccount = activeAccountRef.current;

      // Handle login / logout / account transitions
      if (!storageKey || AUTH_STORAGE_KEYS.has(storageKey)) {
        if (sessionKey !== prevAccount) {
          const generation = ++syncGenerationRef.current;
          clearPendingServerSave();
          activeAccountRef.current = sessionKey;

          if (!sessionKey) {
            // User logged out or session expired:
            // Remove previous account's cart from visible UI without deleting their server-side cart!
            cartRef.current = [];
            setCart([]);
            return;
          }

          // User logged in as sessionKey
          if (!isSyncingRef.current) {
            isSyncingRef.current = true;
            try {
              const hasGuest = isGenuineGuestCart();
              const guestItems = hasGuest ? loadGuestCart() : [];
              const serverRes = await fetchServerCart(sessionKey);

              if (generation !== syncGenerationRef.current || sessionKey !== getSessionKey()) return;

              if (serverRes.ok) {
                let finalCart: CartItem[];
                if (hasGuest && guestItems.length > 0) {
                  // Merge genuine guest items into this user's server cart
                  finalCart = mergeCartItems(guestItems, serverRes.items, { combineQuantities: true });
                  clearGuestCart();
                  await saveServerCart(finalCart, sessionKey);
                } else {
                  // Server is authoritative for this account; do not restore old local items when server cart is empty
                  finalCart = serverRes.items;
                }
                cartRef.current = finalCart;
                setCart(finalCart);
                saveAccountCart(sessionKey, finalCart);
              } else if (serverRes.reason === 'unauthorized') {
                clearAuth();
                activeAccountRef.current = null;
                cartRef.current = [];
                setCart([]);
              } else {
                // Network error: keep local account cart
                const localAccountCart = loadAccountCart(sessionKey);
                cartRef.current = localAccountCart;
                setCart(localAccountCart);
              }
            } finally {
              isSyncingRef.current = false;
            }
          }
          return;
        }
      }

      // Handle cross-tab cart updates
      if (storageKey) {
        if (!prevAccount && storageKey === GUEST_CART_STORAGE_KEY) {
          isApplyingCrossTabRef.current = true;
          const updated = loadGuestCart();
          cartRef.current = updated;
          setCart(updated);
          isApplyingCrossTabRef.current = false;
        } else if (prevAccount && storageKey === getAccountCartStorageKey(prevAccount)) {
          isApplyingCrossTabRef.current = true;
          const updated = loadAccountCart(prevAccount);
          cartRef.current = updated;
          setCart(updated);
          isApplyingCrossTabRef.current = false;
        }
      }
    };

    window.addEventListener('storage', handleStorageChange);
    return () => window.removeEventListener('storage', handleStorageChange);
  }, [clearPendingServerSave, fetchServerCart, saveServerCart]);

  // --- Cart mutation functions ---

  const addToCart = (item: MenuItem) => {
    const existing = cartRef.current.find(i => i.id === item.id);
    let updated: CartItem[];
    if (existing) {
      updated = cartRef.current.map(i => i.id === item.id ? { ...i, quantity: i.quantity + 1 } : i);
    } else {
      updated = [...cartRef.current, { ...item, quantity: 1 }];
    }
    persistCartMutation(updated, false);
    setIsCartOpen(true);
  };

  const replaceCart = (items: CartItem[]) => {
    persistCartMutation(items, false);
    setIsCartOpen(true);
  };

  const removeFromCart = (itemId: string) => {
    const updated = cartRef.current.filter(i => i.id !== itemId);
    persistCartMutation(updated, true);
  };

  const updateQuantity = (itemId: string, delta: number) => {
    const prev = cartRef.current;
    const updated = prev.flatMap(i => {
      if (i.id === itemId) {
        const newQty = i.quantity + delta;
        return newQty > 0 ? [{ ...i, quantity: newQty }] : [];
      }
      return [i];
    });
    const immediate = updated.length < prev.length;
    persistCartMutation(updated, immediate);
  };

  const toggleCart = () => setIsCartOpen(!isCartOpen);

  const clearCart = () => {
    cartVersionRef.current += 1;
    syncGenerationRef.current += 1;
    clearPendingServerSave();
    retryCountRef.current = 0;
    setSyncError(null);
    const currentAccount = activeAccountRef.current ?? getSessionKey();
    clearCheckoutIdempotencyKey(currentAccount);

    cartRef.current = [];
    setCart([]);

    if (currentAccount) {
      saveAccountCart(currentAccount, []);
      saveToServerNow([]);
    } else {
      clearGuestCart();
    }
  };

  const retrySync = useCallback(() => {
    if (retryTimerRef.current) {
      clearTimeout(retryTimerRef.current);
      retryTimerRef.current = null;
    }
    retryCountRef.current = 0;
    const currentAccount = activeAccountRef.current ?? getSessionKey();
    if (currentAccount) {
      void saveServerCart(cartRef.current, currentAccount);
    }
  }, [saveServerCart]);

  const cartCount = cart.reduce((total, item) => total + item.quantity, 0);
  const cartTotal = cart.reduce((total, item) => total + (item.price * item.quantity), 0);

  return (
    <CartContext.Provider value={{
      cart, addToCart, replaceCart, removeFromCart, updateQuantity, cartCount, cartTotal, isCartOpen, toggleCart, clearCart, syncError, retrySync
    }}>
      {children}
    </CartContext.Provider>
  );
};

export const useCart = () => {
  const context = useContext(CartContext);
  if (context === undefined) {
    throw new Error('useCart must be used within a CartProvider');
  }
  return context;
};
