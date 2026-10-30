export const BACKEND_ORIGIN = process.env.NEXT_PUBLIC_BACKEND_ORIGIN ?? 'http://localhost:8082';
export const API_BASE = `${BACKEND_ORIGIN}/api`;
export const SOCKET_URL = process.env.NEXT_PUBLIC_SOCKET_URL ?? 'http://localhost:9092';

export function backendAssetUrl(src: string): string {
  return src.startsWith('/uploads') ? `${BACKEND_ORIGIN}${src}` : src;
}

export interface StoreSettings {
  taxRate: number;
  defaultReadyMinutes: number;
  pickupOrdersEnabled: boolean;
}

const DEFAULT_STORE_SETTINGS: StoreSettings = {
  taxRate: 0.13,
  defaultReadyMinutes: 25,
  pickupOrdersEnabled: true,
};

export async function fetchStoreSettings(): Promise<StoreSettings> {
  try {
    const res = await fetch(`${API_BASE}/store-settings`);
    if (!res.ok) {
      return DEFAULT_STORE_SETTINGS;
    }
    const data = await res.json();
    return {
      taxRate: typeof data.taxRate === 'number' ? data.taxRate : DEFAULT_STORE_SETTINGS.taxRate,
      defaultReadyMinutes: typeof data.defaultReadyMinutes === 'number'
        ? data.defaultReadyMinutes
        : DEFAULT_STORE_SETTINGS.defaultReadyMinutes,
      pickupOrdersEnabled: data.pickupOrdersEnabled !== false,
    };
  } catch {
    return DEFAULT_STORE_SETTINGS;
  }
}

export type OrderStatus = 'NEW' | 'ACCEPTED' | 'READY' | 'PICKED_UP' | 'NO_SHOW';

export interface OrderItem {
  menuItemId: string;
  name: string;
  quantity: number;
  unitPrice: number;
  total: number;
  notes?: string;
}

export interface OrderMessage {
  id: string;
  senderRole: 'CUSTOMER' | 'KDS' | string;
  senderName: string;
  text: string;
  createdAt: string;
}

export interface CustomerOrder {
  id: string;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  pickupDate: string;
  pickupTime: string;
  specialInstructions?: string | null;
  status: OrderStatus;
  averageReadyMinutes: number;
  items: OrderItem[];
  messages: OrderMessage[];
  subtotal: number;
  tax: number;
  total: number;
  createdAt: string;
  acceptedAt?: string | null;
  readyAt?: string | null;
  pickedUpAt?: string | null;
  noShowAt?: string | null;
  reviewed?: boolean;
  idempotencyKey?: string | null;
  customerOrderCount?: number;
  customerNoShowCount?: number;
}

export interface OrderReviewItem {
  menuItemId: string;
  itemName: string;
  rating: number;
  description?: string;
}

export interface OrderReview {
  id: string;
  orderId: string;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  status: 'PENDING' | 'PUBLISHED' | 'HIDDEN' | string;
  items: OrderReviewItem[];
  createdAt: string;
  updatedAt: string;
}

export function getTodayString(): string {
  const today = new Date();
  const year = today.getFullYear();
  const month = String(today.getMonth() + 1).padStart(2, '0');
  const day = String(today.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function formatPickupDateLabel(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  if (!year || !month || !day) {
    return isoDate;
  }
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
  });
}

export function formatPickupTimeLabel(value?: string | null): string {
  if (!value) {
    return '';
  }
  return value.trim().toLowerCase() === 'asap' ? 'As soon as possible' : value;
}

export function formatOrderId(id: string): string {
  return `ORD-${id.slice(-6).toUpperCase()}`;
}

export function formatMoney(value: number): string {
  return `$${Number(value || 0).toFixed(2)}`;
}

export function formatTime(value?: string | null): string {
  if (!value) {
    return '';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

export const ORDER_TRACKING_TOKEN_HEADER = 'X-Order-Tracking-Token';

function trackingTokenStorageKey(orderId: string): string {
  return `orderTrackingToken:${orderId}`;
}

export function storeTrackingToken(orderId: string, token: string): void {
  if (typeof window === 'undefined' || !orderId || !token) {
    return;
  }
  try {
    sessionStorage.setItem(trackingTokenStorageKey(orderId), token);
  } catch {
    // sessionStorage may be unavailable (private mode); access falls back to JWT ownership.
  }
}

export function getStoredTrackingToken(orderId: string): string | null {
  if (typeof window === 'undefined' || !orderId) {
    return null;
  }
  try {
    return sessionStorage.getItem(trackingTokenStorageKey(orderId));
  } catch {
    return null;
  }
}

export function isActiveOrderStatus(status: OrderStatus): boolean {
  return status !== 'PICKED_UP' && status !== 'NO_SHOW';
}

export function orderStatusLabel(status: OrderStatus): string {
  if (status === 'NEW') return 'Received';
  if (status === 'ACCEPTED') return 'In progress';
  if (status === 'READY') return 'Ready for pickup';
  if (status === 'NO_SHOW') return 'No show';
  return 'Picked up';
}

export function sortOrders(orders: CustomerOrder[]): CustomerOrder[] {
  return [...orders].sort((a, b) => {
    const left = new Date(a.createdAt).getTime();
    const right = new Date(b.createdAt).getTime();
    return right - left;
  });
}

export const ORDER_IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';
export const CHECKOUT_IDEMPOTENCY_KEY_STORAGE = 'checkoutIdempotencyKey';
export const CHECKOUT_CART_FINGERPRINT_STORAGE = 'checkoutCartFingerprint';

export function getCartFingerprint(items: { id?: string; menuItemId?: string; quantity: number }[]): string {
  return items
    .map(i => `${i.id || i.menuItemId || ''}:${i.quantity}`)
    .sort()
    .join('|');
}

export function getOrCreateCheckoutIdempotencyKey(cart: { id?: string; menuItemId?: string; quantity: number }[]): string {
  if (typeof sessionStorage === 'undefined') {
    return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : `lgb-${Date.now()}`;
  }
  try {
    const currentFingerprint = getCartFingerprint(cart);
    const savedFingerprint = sessionStorage.getItem(CHECKOUT_CART_FINGERPRINT_STORAGE);
    const savedKey = sessionStorage.getItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE);

    if (savedKey && savedFingerprint === currentFingerprint) {
      return savedKey;
    }

    const newKey = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `lgb-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
    sessionStorage.setItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE, newKey);
    sessionStorage.setItem(CHECKOUT_CART_FINGERPRINT_STORAGE, currentFingerprint);
    return newKey;
  } catch {
    return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : `lgb-${Date.now()}`;
  }
}

export function clearCheckoutIdempotencyKey(): void {
  if (typeof sessionStorage === 'undefined') return;
  try {
    sessionStorage.removeItem(CHECKOUT_IDEMPOTENCY_KEY_STORAGE);
    sessionStorage.removeItem(CHECKOUT_CART_FINGERPRINT_STORAGE);
  } catch {
    // ignore
  }
}

