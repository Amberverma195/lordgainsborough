'use client';

import React, { createContext, useContext, useState, useEffect, useRef, useCallback } from 'react';
import { MenuItem } from '@/lib/mockData';
import { API_BASE, clearCheckoutIdempotencyKey } from '@/lib/orders';

export interface CartItem extends MenuItem {
  quantity: number;
}

interface ServerCartItem {
  menuItemId: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  quantity: number;
}

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
}

const CART_STORAGE_KEY = 'lord_gainsborough_cart';
const AUTH_STORAGE_KEYS = new Set(['authToken', 'userRole', 'userName', 'userEmail', 'userPhone']);

const CartContext = createContext<CartContextType | undefined>(undefined);

// --- Helpers for converting between frontend and server cart shapes ---

function toServerItems(items: CartItem[]): ServerCartItem[] {
  return items.map(item => ({
    menuItemId: item.id,
    name: item.name,
    description: item.description,
    price: item.price,
    category: item.category,
    image: item.image,
    quantity: item.quantity,
  }));
}

function fromServerItems(items: ServerCartItem[]): CartItem[] {
  return items.map(item => ({
    id: item.menuItemId,
    name: item.name,
    description: item.description,
    price: item.price,
    category: item.category,
    image: item.image,
    quantity: item.quantity,
  }));
}

function mergeCartItems(localItems: CartItem[], serverItems: CartItem[]): CartItem[] {
  const merged = new Map<string, CartItem>();

  // Add server items first
  for (const item of serverItems) {
    merged.set(item.id, { ...item });
  }

  // Merge local items — keep higher quantity for duplicates
  for (const item of localItems) {
    const existing = merged.get(item.id);
    if (existing) {
      existing.quantity = Math.max(existing.quantity, item.quantity);
    } else {
      merged.set(item.id, { ...item });
    }
  }

  return Array.from(merged.values());
}

function getToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('authToken');
}

function saveLocalCart(items: CartItem[]) {
  if (typeof window === 'undefined') return;
  localStorage.setItem(CART_STORAGE_KEY, JSON.stringify(items));
}

// --- Provider ---

export const CartProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [cart, setCart] = useState<CartItem[]>([]);
  const [isCartOpen, setIsCartOpen] = useState(false);
  const [initialised, setInitialised] = useState(false);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isSyncingRef = useRef(false);
  const pendingServerSavesRef = useRef(0);
  const cartRef = useRef<CartItem[]>([]);

  // --- Server API calls ---

  const fetchServerCart = useCallback(async (token: string): Promise<CartItem[]> => {
    try {
      const res = await fetch(`${API_BASE}/cart`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!res.ok) return [];
      const data = await res.json();
      return fromServerItems(data.items || []);
    } catch {
      console.error('Failed to fetch server cart');
      return [];
    }
  }, []);

  const saveServerCart = useCallback(async (items: CartItem[], token: string) => {
    pendingServerSavesRef.current += 1;
    try {
      const res = await fetch(`${API_BASE}/cart`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(toServerItems(items)),
      });
      if (!res.ok) {
        console.error('Failed to save server cart');
      }
    } catch {
      console.error('Failed to save server cart');
    } finally {
      pendingServerSavesRef.current = Math.max(0, pendingServerSavesRef.current - 1);
    }
  }, []);

  const clearPendingServerSave = useCallback(() => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
  }, []);

  const saveToServerNow = useCallback((items: CartItem[]) => {
    const token = getToken();
    if (!token) return;

    clearPendingServerSave();
    void saveServerCart(items, token);
  }, [clearPendingServerSave, saveServerCart]);

  // Debounced save — fires 1s after last cart mutation
  const debouncedSaveToServer = useCallback((items: CartItem[]) => {
    const token = getToken();
    if (!token) return;

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = setTimeout(() => {
      debounceTimerRef.current = null;
      void saveServerCart(items, token);
    }, 1000);
  }, [saveServerCart]);

  // --- Initialisation: load from localStorage, then merge with server if logged in ---

  useEffect(() => {
    const init = async () => {
      // 1. Load localStorage cart
      let localCart: CartItem[] = [];
      const savedCart = localStorage.getItem(CART_STORAGE_KEY);
      if (savedCart) {
        try {
          localCart = JSON.parse(savedCart);
        } catch {
          console.error('Failed to parse saved cart');
        }
      }

      // 2. If logged in, fetch server cart and merge
      const token = getToken();
      if (token) {
        isSyncingRef.current = true;
        const serverCart = await fetchServerCart(token);
        const merged = mergeCartItems(localCart, serverCart);
        cartRef.current = merged;
        setCart(merged);
        saveLocalCart(merged);

        // Persist the merged result to server if there were local-only items
        if (localCart.length > 0 && JSON.stringify(merged) !== JSON.stringify(serverCart)) {
          await saveServerCart(merged, token);
        }
        isSyncingRef.current = false;
      } else {
        cartRef.current = localCart;
        setCart(localCart);
      }

      setInitialised(true);
    };

    init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // --- Persist to localStorage whenever cart changes (after init) ---

  useEffect(() => {
    if (!initialised) return;
    cartRef.current = cart;
    saveLocalCart(cart);
  }, [cart, initialised]);

  // --- Listen for login/logout (storage events) to trigger sync ---

  useEffect(() => {
    const handleStorageChange = async (event: StorageEvent | Event) => {
      const storageKey = 'key' in event ? event.key : null;
      if (storageKey && !AUTH_STORAGE_KEYS.has(storageKey)) return;
      if (pendingServerSavesRef.current > 0) return;

      const token = getToken();
      if (token && !isSyncingRef.current) {
        // User just logged in — merge local cart with server cart
        isSyncingRef.current = true;
        try {
          const localCart = [...cartRef.current];
          const serverCart = await fetchServerCart(token);
          const merged = mergeCartItems(localCart, serverCart);
          cartRef.current = merged;
          setCart(merged);
          saveLocalCart(merged);
          await saveServerCart(merged, token);
        } finally {
          isSyncingRef.current = false;
        }
      }
    };

    window.addEventListener('storage', handleStorageChange);
    return () => window.removeEventListener('storage', handleStorageChange);
  }, [fetchServerCart, saveServerCart]);

  // --- Cart mutation functions ---

  const addToCart = (item: MenuItem) => {
    setCart(prev => {
      const existing = prev.find(i => i.id === item.id);
      let updated: CartItem[];
      if (existing) {
        updated = prev.map(i => i.id === item.id ? { ...i, quantity: i.quantity + 1 } : i);
      } else {
        updated = [...prev, { ...item, quantity: 1 }];
      }
      cartRef.current = updated;
      saveLocalCart(updated);
      debouncedSaveToServer(updated);
      return updated;
    });
    setIsCartOpen(true);
  };

  const replaceCart = (items: CartItem[]) => {
    cartRef.current = items;
    saveLocalCart(items);
    setCart(items);
    debouncedSaveToServer(items);
    setIsCartOpen(true);
  };

  const removeFromCart = (itemId: string) => {
    setCart(prev => {
      const updated = prev.filter(i => i.id !== itemId);
      cartRef.current = updated;
      saveLocalCart(updated);
      saveToServerNow(updated);
      return updated;
    });
  };

  const updateQuantity = (itemId: string, delta: number) => {
    setCart(prev => {
      const updated = prev.flatMap(i => {
        if (i.id === itemId) {
          const newQty = i.quantity + delta;
          return newQty > 0 ? [{ ...i, quantity: newQty }] : [];
        }
        return [i];
      });
      cartRef.current = updated;
      saveLocalCart(updated);
      if (updated.length < prev.length) {
        saveToServerNow(updated);
      } else {
        debouncedSaveToServer(updated);
      }
      return updated;
    });
  };

  const toggleCart = () => setIsCartOpen(!isCartOpen);

  const clearCart = () => {
    clearPendingServerSave();
    clearCheckoutIdempotencyKey();
    cartRef.current = [];
    saveLocalCart([]);
    setCart([]);
    saveToServerNow([]);
  };

  const cartCount = cart.reduce((total, item) => total + item.quantity, 0);
  const cartTotal = cart.reduce((total, item) => total + (item.price * item.quantity), 0);

  return (
    <CartContext.Provider value={{
      cart, addToCart, replaceCart, removeFromCart, updateQuantity, cartCount, cartTotal, isCartOpen, toggleCart, clearCart
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
