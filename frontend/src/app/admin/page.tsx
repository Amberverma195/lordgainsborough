'use client';

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import Link from 'next/link';
import styles from './admin.module.css';
import { motion, AnimatePresence } from 'framer-motion';
import { useRequireAdmin } from '@/hooks/useRequireAdmin';
import { getAuthHeaders, getAuthHeadersOnly } from '@/lib/auth';
import {
  API_BASE,
  CustomerOrder,
  OrderReview,
  OrderStatus,
  backendAssetUrl,
  formatMoney,
  formatOrderId,
  formatPickupDateLabel,
  formatTime,
  getTodayString,
  orderStatusLabel,
  sortOrders,
} from '@/lib/orders';
import { BarChart3, CalendarDays, Eye, EyeOff, MessageSquareText, MonitorCog, Search, Store, Trash2, UserRoundSearch } from 'lucide-react';

const MAX_KDS_ALLOWED_IPS = 3;

function parseKdsAllowedIps(raw: string): string[] {
  if (!raw) {
    return [];
  }
  const seen = new Set<string>();
  const ips: string[] = [];
  for (const part of raw.split(/[,\n;]+/)) {
    const trimmed = part.trim();
    if (!trimmed) {
      continue;
    }
    const key = trimmed.toLowerCase();
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    ips.push(trimmed);
    if (ips.length >= MAX_KDS_ALLOWED_IPS) {
      break;
    }
  }
  return ips;
}

function joinKdsAllowedIps(ips: string[]): string {
  return ips.slice(0, MAX_KDS_ALLOWED_IPS).join('\n');
}

function maskIpAddress(ip: string): string {
  if (!ip) {
    return '••••••••';
  }
  if (ip.includes('/')) {
    const [network, prefix] = ip.split('/');
    return `${maskIpAddress(network)}/${prefix || '?'}`;
  }
  if (ip.includes(':')) {
    const parts = ip.split(':').filter(Boolean);
    if (parts.length <= 2) {
      return '••••:••••';
    }
    return `${parts[0]}:••••:••••:${parts[parts.length - 1]}`;
  }
  const parts = ip.split('.');
  if (parts.length === 4) {
    return `${parts[0]}.${parts[1]}.•.•`;
  }
  if (ip.length <= 4) {
    return '••••';
  }
  return `${ip.slice(0, 2)}${'•'.repeat(Math.min(ip.length - 2, 8))}`;
}

interface Category {
  id: string;
  name: string;
  description: string;
  displayOrder: number;
}

interface MenuItem {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  available: boolean;
  mostLoved: boolean;
}

interface Reservation {
  id: string;
  name: string;
  email: string;
  phone: string;
  date: string;
  time: string;
  partySize: number;
  specialRequests: string;
  status: string;
  createdAt: string;
}

interface StoreSettings {
  storeName: string;
  contactPhone: string;
  contactEmail: string;
  taxRate: number;
  defaultReadyMinutes: number;
  pickupOrdersEnabled: boolean;
  kdsAllowedIp: string;
}

function normalizeStoreSettings(data: Partial<StoreSettings> | null | undefined): StoreSettings {
  return {
    storeName: data?.storeName || 'Lord Gainsborough',
    contactPhone: data?.contactPhone || '',
    contactEmail: data?.contactEmail || '',
    taxRate: typeof data?.taxRate === 'number' ? data.taxRate : 0.13,
    defaultReadyMinutes: typeof data?.defaultReadyMinutes === 'number' ? data.defaultReadyMinutes : 25,
    pickupOrdersEnabled: data?.pickupOrdersEnabled !== false,
    kdsAllowedIp: joinKdsAllowedIps(parseKdsAllowedIps(typeof data?.kdsAllowedIp === 'string' ? data.kdsAllowedIp : '')),
  };
}

function storeSettingsEqual(a: StoreSettings, b: StoreSettings): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

interface AdminCustomerSearchResult {
  id: string;
  name: string;
  email: string;
  phone: string;
  role: string;
  memberSince?: string;
  noShowCount: number;
  pickupDisabled: boolean;
  orders: CustomerOrder[];
}

const READY_MINUTE_OPTIONS = [10, 15, 20, 25, 30, 35, 40, 45, 60];

function createSalesWindow() {
  return {
    today: getTodayString(),
    weekStart: Date.now() - 7 * 24 * 60 * 60 * 1000,
  };
}

export default function AdminPage() {
  const isAuthorized = useRequireAdmin();
  const [activeTab, setActiveTab] = useState<'menu' | 'categories' | 'reservations' | 'orders' | 'reviews' | 'mostloved' | 'users' | 'settings'>('menu');
  const [categories, setCategories] = useState<Category[]>([]);
  const [menuItems, setMenuItems] = useState<MenuItem[]>([]);
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [orders, setOrders] = useState<CustomerOrder[]>([]);
  const [reviews, setReviews] = useState<OrderReview[]>([]);
  const [selectedOrder, setSelectedOrder] = useState<CustomerOrder | null>(null);
  const [orderStatusFilter, setOrderStatusFilter] = useState<'ALL' | OrderStatus>('ALL');
  const [orderPeriodFilter, setOrderPeriodFilter] = useState<'all' | 'today' | 'week'>('all');
  const [orderDateFilter, setOrderDateFilter] = useState(getTodayString());
  const [orderSearchQuery, setOrderSearchQuery] = useState('');
  const [showMenuForm, setShowMenuForm] = useState(false);
  const [showCategoryForm, setShowCategoryForm] = useState(false);
  const [editingItem, setEditingItem] = useState<MenuItem | null>(null);
  const [editingCategory, setEditingCategory] = useState<Category | null>(null);
  const [filterCategory, setFilterCategory] = useState('All');
  const [notification, setNotification] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [confirmModal, setConfirmModal] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
  }>({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: () => {},
  });

  // Menu form state
  const [menuForm, setMenuForm] = useState({
    name: '',
    description: '',
    price: '',
    category: '',
    image: '',
    available: true,
  });

  // Category form state
  const [categoryForm, setCategoryForm] = useState({
    name: '',
    description: '',
    displayOrder: '',
  });

  const [storeSettings, setStoreSettings] = useState<StoreSettings>(() => normalizeStoreSettings(null));
  const [savedStoreSettings, setSavedStoreSettings] = useState<StoreSettings>(() => normalizeStoreSettings(null));
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [kdsIpDraft, setKdsIpDraft] = useState('');
  const [kdsIpError, setKdsIpError] = useState('');
  const [detectedClientIp, setDetectedClientIp] = useState('');
  const [revealedKdsIps, setRevealedKdsIps] = useState<Record<string, boolean>>({});
  const [userSearchQuery, setUserSearchQuery] = useState('');
  const [userSearchResults, setUserSearchResults] = useState<AdminCustomerSearchResult[]>([]);
  const [userSearchLoading, setUserSearchLoading] = useState(false);
  const [userSearchError, setUserSearchError] = useState('');
  const [userSearchSearched, setUserSearchSearched] = useState(false);
  const [salesWindow] = useState(createSalesWindow);

  const showNotification = (message: string, type: 'success' | 'error') => {
    setNotification({ message, type });
    setTimeout(() => setNotification(null), 3000);
  };

  const fetchCategories = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/categories`);
      const data = await res.json();
      setCategories(data);
    } catch { showNotification('Failed to load categories', 'error'); }
  }, []);

  const fetchMenuItems = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/menu`);
      const data = await res.json();
      setMenuItems(data);
    } catch { showNotification('Failed to load menu items', 'error'); }
  }, []);

  const fetchReservations = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/reservations`, {
        headers: getAuthHeaders(false),
      });
      const data = await res.json();
      setReservations(data);
    } catch { showNotification('Failed to load reservations', 'error'); }
  }, []);

  const fetchOrders = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/kds/orders/all`, {
        headers: getAuthHeaders(false),
      });
      if (!res.ok) {
        showNotification(
          res.status === 404
            ? 'Failed to load orders — restart the backend or sign in again as admin'
            : `Failed to load orders (${res.status})`,
          'error',
        );
        return;
      }
      const data = await res.json();
      setOrders(sortOrders(Array.isArray(data) ? data : []));
    } catch {
      showNotification('Failed to load orders — is the backend running on port 8082?', 'error');
    }
  }, []);

  const fetchReviews = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/kds/reviews`, {
        headers: getAuthHeaders(false),
      });
      const data = await res.json();
      setReviews(data);
    } catch { showNotification('Failed to load reviews', 'error'); }
  }, []);

  const fetchStoreSettings = useCallback(async (options?: { notifyOnError?: boolean }) => {
    try {
      const res = await fetch(`${API_BASE}/store-settings`, {
        headers: getAuthHeaders(false),
      });
      if (res.status === 404) {
        if (options?.notifyOnError) {
          showNotification('Store settings unavailable — restart the backend to enable this feature', 'error');
        }
        return;
      }
      if (!res.ok) {
        throw new Error('Failed to load store settings');
      }
      const data = await res.json();
      const normalized = normalizeStoreSettings(data);
      setStoreSettings(normalized);
      setSavedStoreSettings(normalized);
    } catch {
      if (options?.notifyOnError) {
        showNotification('Failed to load store settings', 'error');
      }
    }
  }, []);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      fetchCategories();
      fetchMenuItems();
      fetchReservations();
      fetchOrders();
      fetchReviews();
    }, 0);

    return () => window.clearTimeout(timeout);
  }, [fetchCategories, fetchMenuItems, fetchReservations, fetchOrders, fetchReviews]);

  useEffect(() => {
    if (activeTab !== 'settings') {
      return;
    }
    const timeout = window.setTimeout(() => {
      fetchStoreSettings({ notifyOnError: true });
      fetch(`${API_BASE}/admin/client-ip`, { headers: getAuthHeaders(false) })
        .then(async res => {
          if (!res.ok) {
            return;
          }
          const data = await res.json();
          if (typeof data?.ip === 'string' && data.ip) {
            setDetectedClientIp(data.ip);
          }
        })
        .catch(() => {
          // Ignore — KDS IP helper is optional.
        });
    }, 0);

    return () => window.clearTimeout(timeout);
  }, [activeTab, fetchStoreSettings]);

  // --- Category CRUD ---
  const handleCategorySubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const payload = {
      name: categoryForm.name,
      description: categoryForm.description,
      displayOrder: parseInt(categoryForm.displayOrder) || 0,
    };

    try {
      if (editingCategory) {
        await fetch(`${API_BASE}/categories/${editingCategory.id}`, {
          method: 'PUT',
          headers: getAuthHeaders(),
          body: JSON.stringify(payload),
        });
        showNotification('Category updated', 'success');
      } else {
        await fetch(`${API_BASE}/categories`, {
          method: 'POST',
          headers: getAuthHeaders(),
          body: JSON.stringify(payload),
        });
        showNotification('Category created', 'success');
      }
      setCategoryForm({ name: '', description: '', displayOrder: '' });
      setEditingCategory(null);
      setShowCategoryForm(false);
      fetchCategories();
    } catch { showNotification('Failed to save category', 'error'); }
  };

  const deleteCategory = (id: string) => {
    setConfirmModal({
      isOpen: true,
      title: 'Delete Category',
      message: 'Are you sure? This will not delete items in this category.',
      onConfirm: async () => {
        try {
          await fetch(`${API_BASE}/categories/${id}`, { method: 'DELETE', headers: getAuthHeaders(false) });
          showNotification('Category deleted', 'success');
          fetchCategories();
          setConfirmModal(p => ({ ...p, isOpen: false }));
        } catch { showNotification('Failed to delete category', 'error'); }
      }
    });
  };

  const searchUsers = useCallback(async (rawQuery?: string) => {
    const query = (rawQuery ?? userSearchQuery).trim();
    setUserSearchError('');
    setUserSearchSearched(true);

    if (query.length < 2) {
      setUserSearchResults([]);
      setUserSearchError('Enter at least 2 characters to search.');
      return;
    }

    setUserSearchLoading(true);
    try {
      const res = await fetch(`${API_BASE}/admin/customers/search?q=${encodeURIComponent(query)}`, {
        headers: getAuthHeaders(false),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => null);
        throw new Error(typeof data?.message === 'string' ? data.message : 'Search failed');
      }
      const data = await res.json();
      setUserSearchResults(Array.isArray(data) ? data : []);
    } catch (error) {
      setUserSearchResults([]);
      setUserSearchError(error instanceof Error ? error.message : 'Search failed');
    } finally {
      setUserSearchLoading(false);
    }
  }, [userSearchQuery]);

  const handleSettingsSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!settingsDirty || settingsLoading) {
      return;
    }
    setSettingsLoading(true);
    try {
      const res = await fetch(`${API_BASE}/store-settings`, {
        method: 'PUT',
        headers: getAuthHeaders(),
        body: JSON.stringify(storeSettings),
      });
      if (!res.ok) {
        throw new Error('Failed to save store settings');
      }
      const data = await res.json();
      const normalized = normalizeStoreSettings(data);
      setStoreSettings(normalized);
      setSavedStoreSettings(normalized);
      showNotification('Store settings saved', 'success');
    } catch {
      showNotification('Failed to save store settings', 'error');
    } finally {
      setSettingsLoading(false);
    }
  };

  // --- Menu Item CRUD ---
  const handleMenuSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const payload = {
      name: menuForm.name,
      description: menuForm.description,
      price: parseFloat(menuForm.price),
      category: menuForm.category,
      image: menuForm.image,
      available: menuForm.available,
    };

    try {
      if (editingItem) {
        await fetch(`${API_BASE}/menu/${editingItem.id}`, {
          method: 'PUT',
          headers: getAuthHeaders(),
          body: JSON.stringify(payload),
        });
        showNotification('Menu item updated', 'success');
      } else {
        await fetch(`${API_BASE}/menu`, {
          method: 'POST',
          headers: getAuthHeaders(),
          body: JSON.stringify(payload),
        });
        showNotification('Menu item created', 'success');
      }
      resetMenuForm();
      fetchMenuItems();
    } catch { showNotification('Failed to save menu item', 'error'); }
  };

  const toggleAvailability = async (id: string) => {
    try {
      await fetch(`${API_BASE}/menu/${id}/availability`, { method: 'PATCH', headers: getAuthHeaders(false) });
      fetchMenuItems();
    } catch { showNotification('Failed to toggle availability', 'error'); }
  };

  const toggleMostLoved = async (id: string) => {
    try {
      await fetch(`${API_BASE}/menu/${id}/most-loved`, { method: 'PATCH', headers: getAuthHeaders(false) });
      fetchMenuItems();
      showNotification('Most Loved status updated', 'success');
    } catch { showNotification('Failed to update Most Loved status', 'error'); }
  };

  const deleteMenuItem = (id: string) => {
    setConfirmModal({
      isOpen: true,
      title: 'Delete Menu Item',
      message: 'Are you sure you want to delete this item? This action cannot be undone.',
      onConfirm: async () => {
        try {
          await fetch(`${API_BASE}/menu/${id}`, { method: 'DELETE', headers: getAuthHeaders(false) });
          showNotification('Item deleted', 'success');
          fetchMenuItems();
          setConfirmModal(p => ({ ...p, isOpen: false }));
        } catch { showNotification('Failed to delete item', 'error'); }
      }
    });
  };

  const deleteReservation = (id: string) => {
    setConfirmModal({
      isOpen: true,
      title: 'Delete Reservation',
      message: 'Are you sure you want to delete this reservation? This cannot be undone.',
      onConfirm: async () => {
        try {
          await fetch(`${API_BASE}/reservations/${id}`, { method: 'DELETE', headers: getAuthHeaders(false) });
          showNotification('Reservation deleted', 'success');
          fetchReservations();
          setConfirmModal(p => ({ ...p, isOpen: false }));
        } catch { showNotification('Failed to delete reservation', 'error'); }
      }
    });
  };

  const updateReservationStatus = async (id: string, status: string) => {
    try {
      await fetch(`${API_BASE}/reservations/${id}/status`, {
        method: 'PATCH',
        headers: getAuthHeaders(),
        body: JSON.stringify({ status })
      });
      showNotification(`Reservation ${status.toLowerCase()}`, 'success');
      fetchReservations();
    } catch { showNotification('Failed to update status', 'error'); }
  };

  const updateReviewStatus = async (id: string, status: 'PUBLISHED' | 'HIDDEN' | 'PENDING') => {
    try {
      await fetch(`${API_BASE}/kds/reviews/${id}/status`, {
        method: 'PATCH',
        headers: getAuthHeaders(),
        body: JSON.stringify({ status }),
      });
      showNotification(`Review ${status.toLowerCase()}`, 'success');
      fetchReviews();
    } catch { showNotification('Failed to update review', 'error'); }
  };

  const startEditItem = (item: MenuItem) => {
    setEditingItem(item);
    setMenuForm({
      name: item.name,
      description: item.description,
      price: item.price.toString(),
      category: item.category,
      image: item.image,
      available: item.available,
    });
    setShowMenuForm(true);
  };

  const startEditCategory = (cat: Category) => {
    setEditingCategory(cat);
    setCategoryForm({
      name: cat.name,
      description: cat.description,
      displayOrder: cat.displayOrder.toString(),
    });
    setShowCategoryForm(true);
  };

  const resetMenuForm = () => {
    setMenuForm({ name: '', description: '', price: '', category: '', image: '', available: true });
    setEditingItem(null);
    setShowMenuForm(false);
  };

  const resetCategoryForm = () => {
    setCategoryForm({ name: '', description: '', displayOrder: '' });
    setEditingCategory(null);
    setShowCategoryForm(false);
  };

  const handleImageUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);

    try {
      const res = await fetch(`${API_BASE}/menu/upload-image`, {
        method: 'POST',
        headers: getAuthHeadersOnly(),
        body: formData,
      });
      const imageUrl = await res.text();
      setMenuForm(prev => ({ ...prev, image: imageUrl }));
      showNotification('Image uploaded', 'success');
    } catch { showNotification('Failed to upload image', 'error'); }
  };

  const filteredItems = filterCategory === 'All'
    ? menuItems
    : menuItems.filter(item => item.category === filterCategory);

  const kdsAllowedIps = useMemo(
    () => parseKdsAllowedIps(storeSettings.kdsAllowedIp),
    [storeSettings.kdsAllowedIp],
  );

  const settingsDirty = useMemo(
    () => !storeSettingsEqual(normalizeStoreSettings(storeSettings), savedStoreSettings),
    [storeSettings, savedStoreSettings],
  );

  const updateKdsAllowedIps = (ips: string[]) => {
    setStoreSettings(prev => ({
      ...prev,
      kdsAllowedIp: joinKdsAllowedIps(ips),
    }));
  };

  const addKdsAllowedIp = () => {
    const nextIp = kdsIpDraft.trim();
    setKdsIpError('');
    if (!nextIp) {
      setKdsIpError('Enter an IP address to add.');
      return;
    }
    if (kdsAllowedIps.length >= MAX_KDS_ALLOWED_IPS) {
      setKdsIpError(`You can add up to ${MAX_KDS_ALLOWED_IPS} IP addresses.`);
      return;
    }
    if (kdsAllowedIps.some(ip => ip.toLowerCase() === nextIp.toLowerCase())) {
      setKdsIpError('That IP address is already on the list.');
      return;
    }
    updateKdsAllowedIps([...kdsAllowedIps, nextIp]);
    setKdsIpDraft('');
  };

  const removeKdsAllowedIp = (ipToRemove: string) => {
    updateKdsAllowedIps(kdsAllowedIps.filter(ip => ip !== ipToRemove));
    setRevealedKdsIps(prev => {
      const next = { ...prev };
      delete next[ipToRemove];
      return next;
    });
  };

  const salesSummary = useMemo(() => {
    const { today, weekStart } = salesWindow;

    const isInPeriod = (order: CustomerOrder) => {
      if (orderDateFilter) {
        return order.pickupDate === orderDateFilter;
      }
      if (orderPeriodFilter === 'all') {
        return true;
      }
      const created = new Date(order.createdAt).getTime();
      if (Number.isNaN(created)) {
        return false;
      }
      if (orderPeriodFilter === 'today') {
        return order.createdAt.slice(0, 10) === today;
      }
      return created >= weekStart;
    };

    const matchesSearch = (order: CustomerOrder) => {
      const query = orderSearchQuery.trim().toLowerCase();
      if (!query) {
        return true;
      }

      const queryDigits = query.replace(/\D/g, '');
      const phoneDigits = (order.customerPhone || '').replace(/\D/g, '');
      const formattedId = formatOrderId(order.id).toLowerCase();
      const rawId = (order.id || '').toLowerCase();
      const name = (order.customerName || '').toLowerCase();

      if (name.includes(query)) {
        return true;
      }
      if (formattedId.includes(query) || rawId.includes(query.replace(/^#/, ''))) {
        return true;
      }
      if (queryDigits && phoneDigits.includes(queryDigits)) {
        return true;
      }
      return false;
    };

    const matchesFilters = (order: CustomerOrder) => {
      if (orderStatusFilter !== 'ALL' && order.status !== orderStatusFilter) {
        return false;
      }
      if (!isInPeriod(order)) {
        return false;
      }
      return matchesSearch(order);
    };

    const filteredOrders = sortOrders(orders.filter(matchesFilters));
    const completed = orders.filter(order => order.status === 'PICKED_UP');
    const completedInPeriod = completed.filter(isInPeriod);
    const active = orders.filter(order => {
      if (order.status === 'PICKED_UP' || order.status === 'NO_SHOW') {
        return false;
      }
      return isInPeriod(order);
    });

    const statusCounts = orders.filter(isInPeriod).reduce<Record<string, number>>((acc, order) => {
      acc[order.status] = (acc[order.status] || 0) + 1;
      return acc;
    }, {});

    const topItemsMap = new Map<string, { name: string; quantity: number; revenue: number }>();
    completedInPeriod.forEach(order => {
      order.items.forEach(item => {
        const existing = topItemsMap.get(item.menuItemId) || {
          name: item.name,
          quantity: 0,
          revenue: 0,
        };
        existing.quantity += item.quantity;
        existing.revenue += item.total;
        topItemsMap.set(item.menuItemId, existing);
      });
    });

    const topItems = Array.from(topItemsMap.values())
      .sort((a, b) => b.revenue - a.revenue)
      .slice(0, 5);

    const sumTotal = (list: CustomerOrder[]) => list.reduce((sum, order) => sum + (order.total || 0), 0);
    const sumTax = (list: CustomerOrder[]) => list.reduce((sum, order) => sum + (order.tax || 0), 0);
    const sumSubtotal = (list: CustomerOrder[]) => list.reduce((sum, order) => sum + (order.subtotal || 0), 0);

    const totalRevenue = sumTotal(completed);
    const periodRevenue = sumTotal(completedInPeriod);

    return {
      filteredOrders,
      totalRevenue,
      periodRevenue,
      totalTax: sumTax(completed),
      totalSubtotal: sumSubtotal(completed),
      periodTax: sumTax(completedInPeriod),
      periodSubtotal: sumSubtotal(completedInPeriod),
      completedCount: completed.length,
      periodCompletedCount: completedInPeriod.length,
      activeCount: active.length,
      noShowCount: statusCounts.NO_SHOW || 0,
      averageOrderValue: completed.length ? totalRevenue / completed.length : 0,
      statusCounts,
      topItems,
    };
  }, [orders, orderPeriodFilter, orderStatusFilter, orderDateFilter, orderSearchQuery, salesWindow]);

  if (isAuthorized === null) {
    return (
      <div className={styles.container} style={{ minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--secondary)' }}>
        Verifying access...
      </div>
    );
  }

  return (
    <div className={styles.container}>
      {/* Notification */}
      <AnimatePresence>
        {notification && (
          <motion.div
            className={`${styles.notification} ${styles[notification.type]}`}
            initial={{ opacity: 0, y: -20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -20 }}
          >
            {notification.message}
          </motion.div>
        )}
      </AnimatePresence>

      <div className={styles.adminLayout}>
        <aside className={styles.sidebar}>
          <div className={styles.sidebarTop}>
            <p className={styles.sidebarTitle}>Admin</p>
            <Link href="/kds" className={styles.kdsLink}>
              <MonitorCog size={18} /> KDS
            </Link>
          </div>

          <nav className={styles.navStack} aria-label="Admin sections">
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'menu' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('menu')}
            >
              <span className={styles.tabIcon}>🍽️</span>
              <span className={styles.navLabel}>Menu Items</span>
              <span className={styles.badge}>{menuItems.length}</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'categories' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('categories')}
            >
              <span className={styles.tabIcon}>📂</span>
              <span className={styles.navLabel}>Categories</span>
              <span className={styles.badge}>{categories.length}</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'reservations' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('reservations')}
            >
              <span className={styles.tabIcon}>📅</span>
              <span className={styles.navLabel}>Reservations</span>
              <span className={styles.badge}>{reservations.length}</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'orders' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('orders')}
            >
              <BarChart3 size={16} />
              <span className={styles.navLabel}>Order History</span>
              <span className={styles.badge}>{orders.length}</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'reviews' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('reviews')}
            >
              <MessageSquareText size={16} />
              <span className={styles.navLabel}>Reviews</span>
              <span className={styles.badge}>{reviews.length}</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'mostloved' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('mostloved')}
            >
              <span className={styles.tabIcon}>❤️</span>
              <span className={styles.navLabel}>Most Loved</span>
              <span className={styles.badge}>{menuItems.filter(i => i.mostLoved).length}</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'users' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('users')}
            >
              <UserRoundSearch size={16} />
              <span className={styles.navLabel}>Search User</span>
            </button>
            <button
              type="button"
              className={`${styles.navItem} ${activeTab === 'settings' ? styles.navItemActive : ''}`}
              onClick={() => setActiveTab('settings')}
            >
              <Store size={16} />
              <span className={styles.navLabel}>Store Settings</span>
            </button>
          </nav>
        </aside>

        <main className={styles.mainPanel}>
      {/* Categories Tab */}
      {activeTab === 'categories' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <h2>Categories</h2>
            <button
              className={styles.addBtn}
              onClick={() => { resetCategoryForm(); setShowCategoryForm(true); }}
            >
              + Add Category
            </button>
          </div>

          <AnimatePresence>
            {showCategoryForm && (
              <motion.form
                className={styles.formCard}
                onSubmit={handleCategorySubmit}
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
              >
                <h3>{editingCategory ? 'Edit Category' : 'New Category'}</h3>
                <div className={styles.formGrid}>
                  <div className={styles.field}>
                    <label>Name</label>
                    <input
                      type="text"
                      value={categoryForm.name}
                      onChange={e => setCategoryForm(p => ({ ...p, name: e.target.value }))}
                      placeholder="e.g. Starters"
                      required
                    />
                  </div>
                  <div className={styles.field}>
                    <label>Display Order</label>
                    <input
                      type="number"
                      value={categoryForm.displayOrder}
                      onChange={e => setCategoryForm(p => ({ ...p, displayOrder: e.target.value }))}
                      placeholder="1"
                    />
                  </div>
                  <div className={`${styles.field} ${styles.fullWidth}`}>
                    <label>Description</label>
                    <input
                      type="text"
                      value={categoryForm.description}
                      onChange={e => setCategoryForm(p => ({ ...p, description: e.target.value }))}
                      placeholder="Brief description"
                    />
                  </div>
                </div>
                <div className={styles.formActions}>
                  <button type="button" className={styles.cancelBtn} onClick={resetCategoryForm}>Cancel</button>
                  <button type="submit" className={styles.saveBtn}>
                    {editingCategory ? 'Update' : 'Create'} Category
                  </button>
                </div>
              </motion.form>
            )}
          </AnimatePresence>

          <div className={styles.categoryGrid}>
            {categories.sort((a, b) => a.displayOrder - b.displayOrder).map(cat => (
              <motion.div
                key={cat.id}
                className={styles.categoryCard}
                layout
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
              >
                <div className={styles.categoryInfo}>
                  <span className={styles.categoryOrder}>#{cat.displayOrder}</span>
                  <h3>{cat.name}</h3>
                  <p>{cat.description}</p>
                  <span className={styles.itemCount}>
                    {menuItems.filter(i => i.category === cat.name).length} items
                  </span>
                </div>
                <div className={styles.cardActions}>
                  <button className={styles.editBtn} onClick={() => startEditCategory(cat)}>Edit</button>
                  <button className={styles.deleteBtn} onClick={() => deleteCategory(cat.id)}>Delete</button>
                </div>
              </motion.div>
            ))}
          </div>
        </motion.div>
      )}

      {/* Menu Items Tab */}
      {activeTab === 'menu' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <div className={styles.sectionLeft}>
              <h2>Menu Items</h2>
              <div className={styles.filterPills}>
                <button
                  className={`${styles.pill} ${filterCategory === 'All' ? styles.activePill : ''}`}
                  onClick={() => setFilterCategory('All')}
                >
                  All
                </button>
                {categories.map(cat => (
                  <button
                    key={cat.id}
                    className={`${styles.pill} ${filterCategory === cat.name ? styles.activePill : ''}`}
                    onClick={() => setFilterCategory(cat.name)}
                  >
                    {cat.name}
                  </button>
                ))}
              </div>
            </div>
            <button
              className={styles.addBtn}
              onClick={() => { resetMenuForm(); setShowMenuForm(true); }}
            >
              + Add Item
            </button>
          </div>

          <AnimatePresence>
            {showMenuForm && (
              <motion.form
                className={styles.formCard}
                onSubmit={handleMenuSubmit}
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
              >
                <h3>{editingItem ? 'Edit Menu Item' : 'New Menu Item'}</h3>
                <div className={styles.formGrid}>
                  <div className={styles.field}>
                    <label>Name</label>
                    <input
                      type="text"
                      value={menuForm.name}
                      onChange={e => setMenuForm(p => ({ ...p, name: e.target.value }))}
                      placeholder="Dish name"
                      required
                    />
                  </div>
                  <div className={styles.field}>
                    <label>Price ($)</label>
                    <input
                      type="number"
                      step="0.01"
                      value={menuForm.price}
                      onChange={e => setMenuForm(p => ({ ...p, price: e.target.value }))}
                      placeholder="0.00"
                      required
                    />
                  </div>
                  <div className={styles.field}>
                    <label>Category</label>
                    <select
                      value={menuForm.category}
                      onChange={e => setMenuForm(p => ({ ...p, category: e.target.value }))}
                      required
                    >
                      <option value="">Select category</option>
                      {categories.map(cat => (
                        <option key={cat.id} value={cat.name}>{cat.name}</option>
                      ))}
                    </select>
                  </div>
                  <div className={styles.field}>
                    <label>Availability</label>
                    <div className={styles.toggleWrapper}>
                      <button
                        type="button"
                        className={`${styles.toggle} ${menuForm.available ? styles.toggleOn : ''}`}
                        onClick={() => setMenuForm(p => ({ ...p, available: !p.available }))}
                      >
                        <span className={styles.toggleKnob} />
                      </button>
                      <span>{menuForm.available ? 'Available' : 'Unavailable'}</span>
                    </div>
                  </div>
                  <div className={`${styles.field} ${styles.fullWidth}`}>
                    <label>Description</label>
                    <textarea
                      value={menuForm.description}
                      onChange={e => setMenuForm(p => ({ ...p, description: e.target.value }))}
                      placeholder="Describe the dish..."
                      rows={3}
                      required
                    />
                  </div>
                  <div className={`${styles.field} ${styles.fullWidth}`}>
                    <label>Image</label>
                    <div className={styles.imageUploadArea}>
                      <input
                        type="file"
                        accept="image/*"
                        onChange={handleImageUpload}
                        className={styles.fileInput}
                        id="imageUpload"
                      />
                      <label htmlFor="imageUpload" className={styles.uploadLabel}>
                        {menuForm.image ? '✅ Image set' : '📷 Choose Image'}
                      </label>
                      <span className={styles.orText}>or</span>
                      <input
                        type="text"
                        value={menuForm.image}
                        onChange={e => setMenuForm(p => ({ ...p, image: e.target.value }))}
                        placeholder="Paste image URL"
                        className={styles.urlInput}
                      />
                    </div>
                  </div>
                </div>
                <div className={styles.formActions}>
                  <button type="button" className={styles.cancelBtn} onClick={resetMenuForm}>Cancel</button>
                  <button type="submit" className={styles.saveBtn}>
                    {editingItem ? 'Update' : 'Create'} Item
                  </button>
                </div>
              </motion.form>
            )}
          </AnimatePresence>

          <div className={styles.menuTable}>
            <div className={styles.tableHeader}>
              <span className={styles.colImage}>Image</span>
              <span className={styles.colName}>Name</span>
              <span className={styles.colCategory}>Category</span>
              <span className={styles.colPrice}>Price</span>
              <span className={styles.colStatus}>Status</span>
              <span className={styles.colActions}>Actions</span>
            </div>
            <AnimatePresence>
              {filteredItems.map(item => (
                <motion.div
                  key={item.id}
                  className={`${styles.tableRow} ${!item.available ? styles.unavailable : ''}`}
                  layout
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 10 }}
                >
                  <span className={styles.colImage}>
                    <div className={styles.thumbnail}>
                      {item.image && item.image !== "" ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={backendAssetUrl(item.image)} alt={item.name} />
                      ) : (
                        <span className={styles.noImage}>🍽️</span>
                      )}
                    </div>
                  </span>
                  <span className={styles.colName}>
                    <strong>{item.name}</strong>
                    <small>{item.description.length > 60 ? item.description.substring(0, 60) + '...' : item.description}</small>
                  </span>
                  <span className={styles.colCategory}>
                    <span className={styles.categoryPill}>{item.category}</span>
                  </span>
                  <span className={styles.colPrice}>${item.price.toFixed(2)}</span>
                  <span className={styles.colStatus}>
                    <button
                      className={`${styles.statusBadge} ${item.available ? styles.statusAvailable : styles.statusUnavailable}`}
                      onClick={() => toggleAvailability(item.id)}
                    >
                      {item.available ? 'Available' : 'Unavailable'}
                    </button>
                  </span>
                  <span className={styles.colActions}>
                    <button className={styles.editBtn} onClick={() => startEditItem(item)}>Edit</button>
                    <button className={styles.deleteBtn} onClick={() => deleteMenuItem(item.id)}>Delete</button>
                  </span>
                </motion.div>
              ))}
            </AnimatePresence>
            {filteredItems.length === 0 && (
              <div className={styles.emptyState}>
                <p>No items found. Click &ldquo;+ Add Item&rdquo; to get started.</p>
              </div>
            )}
          </div>
        </motion.div>
      )}

      {/* Reservations Tab */}
      {activeTab === 'reservations' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <h2>Table Reservations</h2>
          </div>
          <div className={styles.menuTable}>
            <div className={styles.reservationHeader}>
              <span>Name & Contact</span>
              <span>Date & Time</span>
              <span>Party Size</span>
              <span>Requests</span>
              <span>Status</span>
              <span className={styles.colActions}>Actions</span>
            </div>
            <AnimatePresence>
              {reservations.map(res => (
                <motion.div
                  key={res.id}
                  className={styles.reservationRow}
                  layout
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 10 }}
                >
                  <span style={{ display: 'flex', flexDirection: 'column' }}>
                    <strong>{res.name}</strong>
                    <small>{res.email} | {res.phone}</small>
                  </span>
                  <span>
                    {res.date} at {res.time}
                  </span>
                  <span>
                    <span className={styles.categoryPill}>{res.partySize} people</span>
                  </span>
                  <span>
                    <small>{res.specialRequests || 'None'}</small>
                  </span>
                  <span>
                    <span className={`${styles.statusBadge} ${
                      res.status === 'APPROVED' ? styles.statusApproved : 
                      res.status === 'REJECTED' ? styles.statusRejected : 
                      styles.statusPending
                    }`}>
                      {res.status || 'PENDING'}
                    </span>
                  </span>
                  <span className={styles.colActions}>
                    {(!res.status || res.status === 'PENDING') && (
                      <>
                        <button className={styles.approveBtn} onClick={() => updateReservationStatus(res.id, 'APPROVED')}>Approve</button>
                        <button className={styles.rejectBtn} onClick={() => updateReservationStatus(res.id, 'REJECTED')}>Reject</button>
                      </>
                    )}
                    <button className={styles.deleteBtn} onClick={() => deleteReservation(res.id)}>Delete</button>
                  </span>
                </motion.div>
              ))}
            </AnimatePresence>
            {reservations.length === 0 && (
              <div className={styles.emptyState}>
                <p>No table reservations yet.</p>
              </div>
            )}
          </div>
        </motion.div>
      )}

      {/* Sales & Orders Tab */}
      {activeTab === 'orders' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <div className={styles.sectionLeft}>
              <h2>Order History</h2>
              <p style={{ color: 'var(--text-light)', margin: 0 }}>
                {orderDateFilter
                  ? `Pickup orders for ${formatPickupDateLabel(orderDateFilter)} · ${salesSummary.filteredOrders.length} shown`
                  : `Revenue from completed pickups · ${orders.length} total orders`}
              </p>
            </div>
            <div className={styles.orderDateControls}>
              <label className={styles.orderDateField} htmlFor="orderHistoryDate">
                <CalendarDays size={16} aria-hidden />
                <span>Pickup date</span>
                <input
                  id="orderHistoryDate"
                  type="date"
                  value={orderDateFilter || getTodayString()}
                  onChange={event => {
                    setOrderDateFilter(event.target.value || getTodayString());
                    setOrderPeriodFilter('all');
                  }}
                />
              </label>
              {orderDateFilter !== getTodayString() && (
                <button
                  type="button"
                  className={styles.linkBtn}
                  onClick={() => {
                    setOrderDateFilter(getTodayString());
                    setOrderPeriodFilter('all');
                  }}
                >
                  Today
                </button>
              )}
              <button className={styles.addBtn} onClick={fetchOrders}>Refresh</button>
            </div>
          </div>

          <div className={styles.orderSearchRow}>
            <label className={styles.orderSearchField} htmlFor="orderHistorySearch">
              <Search size={16} aria-hidden />
              <input
                id="orderHistorySearch"
                type="search"
                value={orderSearchQuery}
                onChange={event => setOrderSearchQuery(event.target.value)}
                placeholder="Search by name, order ID, or phone"
                autoComplete="off"
              />
            </label>
          </div>

          <div className={styles.statsGrid}>
            <div className={styles.statCard}>
              <span className={styles.statLabel}>Total revenue</span>
              <strong className={styles.statValue}>{formatMoney(salesSummary.totalRevenue)}</strong>
              <small>{salesSummary.completedCount} completed orders</small>
            </div>
            <div className={styles.statCard}>
              <span className={styles.statLabel}>
                {orderDateFilter
                  ? 'Selected day revenue'
                  : orderPeriodFilter === 'today'
                    ? "Today's revenue"
                    : orderPeriodFilter === 'week'
                      ? 'Last 7 days'
                      : 'Filtered revenue'}
              </span>
              <strong className={styles.statValue}>{formatMoney(salesSummary.periodRevenue)}</strong>
              <small>{salesSummary.periodCompletedCount} completed in period</small>
            </div>
            <div className={styles.statCard}>
              <span className={styles.statLabel}>Average order</span>
              <strong className={styles.statValue}>{formatMoney(salesSummary.averageOrderValue)}</strong>
              <small>Based on picked-up orders</small>
            </div>
            <div className={styles.statCard}>
              <span className={styles.statLabel}>Tax collected</span>
              <strong className={styles.statValue}>{formatMoney(salesSummary.totalTax)}</strong>
              <small>Subtotal {formatMoney(salesSummary.totalSubtotal)}</small>
            </div>
            <div className={styles.statCard}>
              <span className={styles.statLabel}>Active orders</span>
              <strong className={styles.statValue}>{salesSummary.activeCount}</strong>
              <small>New, accepted, or ready</small>
            </div>
            <div className={styles.statCard}>
              <span className={styles.statLabel}>No-shows</span>
              <strong className={`${styles.statValue} ${styles.statValueDanger}`}>{salesSummary.noShowCount}</strong>
              <small>Orders marked no-show</small>
            </div>
          </div>

          <div className={styles.salesPanels}>
            <div className={`${styles.salesPanel} premium-card`}>
              <h3>Orders by status</h3>
              <div className={styles.statusBreakdown}>
                {(['NEW', 'ACCEPTED', 'READY', 'PICKED_UP', 'NO_SHOW'] as OrderStatus[]).map(status => (
                  <div key={status} className={styles.statusBreakdownRow}>
                    <span>{orderStatusLabel(status)}</span>
                    <strong>{salesSummary.statusCounts[status] || 0}</strong>
                  </div>
                ))}
              </div>
            </div>

            <div className={`${styles.salesPanel} premium-card`}>
              <h3>Top sellers</h3>
              {salesSummary.topItems.length > 0 ? (
                <div className={styles.topItemsList}>
                  {salesSummary.topItems.map(item => (
                    <div key={item.name} className={styles.topItemRow}>
                      <span>{item.name}</span>
                      <small>{item.quantity} sold</small>
                      <strong>{formatMoney(item.revenue)}</strong>
                    </div>
                  ))}
                </div>
              ) : (
                <p className={styles.panelEmpty}>No completed orders yet.</p>
              )}
            </div>
          </div>

          <div className={styles.salesFilters}>
            <div className={styles.filterPills}>
              <button
                type="button"
                className={`${styles.pill} ${orderStatusFilter === 'ALL' ? styles.activePill : ''}`}
                onClick={() => setOrderStatusFilter('ALL')}
              >
                All statuses
              </button>
              {(['NEW', 'ACCEPTED', 'READY', 'PICKED_UP', 'NO_SHOW'] as OrderStatus[]).map(status => (
                <button
                  key={status}
                  type="button"
                  className={`${styles.pill} ${orderStatusFilter === status ? styles.activePill : ''}`}
                  onClick={() => setOrderStatusFilter(status)}
                >
                  {orderStatusLabel(status)}
                </button>
              ))}
            </div>
          </div>

          <div className={styles.menuTable}>
            <div className={styles.ordersHeader}>
              <span>Order ID</span>
              <span>Name &amp; Phone</span>
              <span>Pickup</span>
              <span>Status</span>
              <span>Total</span>
            </div>
            <AnimatePresence>
              {salesSummary.filteredOrders.map(order => (
                <motion.button
                  type="button"
                  key={order.id}
                  className={styles.orderRow}
                  onClick={() => setSelectedOrder(order)}
                  layout
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 10 }}
                >
                  <span className={styles.orderId}>#{formatOrderId(order.id)}</span>
                  <span className={styles.orderCustomer}>
                    <strong>{order.customerName}</strong>
                    <small>{order.customerPhone}</small>
                  </span>
                  <span>{formatPickupDateLabel(order.pickupDate)} · {order.pickupTime}</span>
                  <span>
                    <span className={`${styles.statusBadge} ${styles.orderStatus}`}>
                      {orderStatusLabel(order.status)}
                    </span>
                  </span>
                  <span className={styles.colPrice}>{formatMoney(order.total)}</span>
                </motion.button>
              ))}
            </AnimatePresence>
            {salesSummary.filteredOrders.length === 0 && (
              <div className={styles.emptyState}>
                <p>
                  {orderSearchQuery.trim()
                    ? 'No orders match this search for the selected date.'
                    : 'No orders for the selected date.'}
                </p>
              </div>
            )}
          </div>
        </motion.div>
      )}

      {/* Reviews Tab */}
      {activeTab === 'reviews' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <h2>Reviews</h2>
            <button className={styles.addBtn} onClick={fetchReviews}>Refresh</button>
          </div>
          <div className={styles.menuTable}>
            <div className={styles.reviewsHeader}>
              <span>Customer</span>
              <span>Order</span>
              <span>Items & Ratings</span>
              <span>Status</span>
              <span>Actions</span>
            </div>
            <AnimatePresence>
              {reviews.map(review => (
                <motion.div
                  key={review.id}
                  className={styles.reviewRow}
                  layout
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 10 }}
                >
                  <span className={styles.orderCustomer}>
                    <strong>{review.customerName}</strong>
                    <small>{review.customerPhone}</small>
                  </span>
                  <span className={styles.orderId}>#{formatOrderId(review.orderId)}</span>
                  <span className={styles.reviewItems}>
                    {review.items.map(item => (
                      <span key={`${review.id}-${item.menuItemId}`}>
                        <strong>{item.itemName}</strong>
                        <small>{item.rating}/5{item.description ? ` - ${item.description}` : ''}</small>
                      </span>
                    ))}
                  </span>
                  <span>
                    <span className={`${styles.statusBadge} ${styles.orderStatus}`}>{review.status}</span>
                  </span>
                  <span className={styles.colActions}>
                    {review.status !== 'PUBLISHED' && (
                      <button className={styles.approveBtn} onClick={() => updateReviewStatus(review.id, 'PUBLISHED')}>Publish</button>
                    )}
                    {review.status !== 'HIDDEN' && (
                      <button className={styles.rejectBtn} onClick={() => updateReviewStatus(review.id, 'HIDDEN')}>Hide</button>
                    )}
                  </span>
                </motion.div>
              ))}
            </AnimatePresence>
            {reviews.length === 0 && (
              <div className={styles.emptyState}>
                <p>No reviews yet.</p>
              </div>
            )}
          </div>
        </motion.div>
      )}

      {/* Search User Tab */}
      {activeTab === 'users' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <div className={styles.sectionLeft}>
              <h2>Search User</h2>
              <p style={{ color: 'var(--text-light)', margin: 0 }}>
                Find a customer by name, email, or phone and review their full order history.
              </p>
            </div>
          </div>

          <form
            className={styles.userSearchBar}
            onSubmit={event => {
              event.preventDefault();
              void searchUsers();
            }}
          >
            <label className={styles.orderSearchField} htmlFor="adminUserSearch">
              <Search size={16} aria-hidden />
              <input
                id="adminUserSearch"
                type="search"
                value={userSearchQuery}
                onChange={event => setUserSearchQuery(event.target.value)}
                placeholder="Search by name, email, or phone"
                autoComplete="off"
              />
            </label>
            <button type="submit" className={styles.addBtn} disabled={userSearchLoading}>
              {userSearchLoading ? 'Searching...' : 'Search'}
            </button>
          </form>

          {userSearchError && <p className={styles.emptyState}>{userSearchError}</p>}

          {!userSearchError && userSearchSearched && !userSearchLoading && userSearchResults.length === 0 && (
            <div className={styles.emptyState}>
              <p>No users matched that search.</p>
            </div>
          )}

          <div className={styles.userSearchResults}>
            {userSearchResults.map(user => (
              <div key={user.id} className={`${styles.formCard} ${styles.userResultCard}`}>
                <div className={styles.userResultHeader}>
                  <div>
                    <h3>{user.name || 'Unnamed customer'}</h3>
                    <p>
                      {user.email || 'No email'}
                      {user.phone ? ` · ${user.phone}` : ''}
                    </p>
                    <small>
                      {user.role || 'MEMBER'}
                      {user.memberSince ? ` · Member since ${user.memberSince}` : ''}
                      {` · ${user.orders?.length || 0} orders`}
                      {` · ${user.noShowCount || 0} no-shows`}
                      {user.pickupDisabled ? ' · Pickup disabled' : ''}
                    </small>
                  </div>
                </div>

                <div className={styles.menuTable}>
                  <div className={styles.ordersHeader}>
                    <span>Order ID</span>
                    <span>Name &amp; Phone</span>
                    <span>Pickup</span>
                    <span>Status</span>
                    <span>Total</span>
                  </div>
                  {(user.orders || []).map(order => (
                    <button
                      type="button"
                      key={order.id}
                      className={styles.orderRow}
                      onClick={() => setSelectedOrder(order)}
                    >
                      <span className={styles.orderId}>#{formatOrderId(order.id)}</span>
                      <span className={styles.orderCustomer}>
                        <strong>{order.customerName}</strong>
                        <small>{order.customerPhone}</small>
                      </span>
                      <span>{formatPickupDateLabel(order.pickupDate)} · {order.pickupTime}</span>
                      <span>
                        <span className={`${styles.statusBadge} ${styles.orderStatus}`}>
                          {orderStatusLabel(order.status)}
                        </span>
                      </span>
                      <span className={styles.colPrice}>{formatMoney(order.total)}</span>
                    </button>
                  ))}
                  {(user.orders || []).length === 0 && (
                    <div className={styles.emptyState}>
                      <p>No orders found for this user.</p>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </motion.div>
      )}

      {/* Store Settings Tab */}
      {activeTab === 'settings' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <h2>Store Settings</h2>
          </div>

          <form className={styles.formCard} onSubmit={handleSettingsSubmit}>
            <h3>Orders &amp; pickup</h3>
            <div className={styles.formGrid}>
              <div className={styles.field}>
                <label htmlFor="taxRate">Tax rate (%)</label>
                <input
                  id="taxRate"
                  type="number"
                  min="0"
                  max="100"
                  step="0.1"
                  value={(storeSettings.taxRate * 100).toFixed(1)}
                  onChange={event => {
                    const percent = Number(event.target.value);
                    setStoreSettings(prev => ({
                      ...prev,
                      taxRate: Number.isFinite(percent) ? Math.min(100, Math.max(0, percent)) / 100 : 0,
                    }));
                  }}
                  required
                />
              </div>
              <div className={styles.field}>
                <label htmlFor="defaultReadyMinutes">Default ready time</label>
                <select
                  id="defaultReadyMinutes"
                  value={storeSettings.defaultReadyMinutes}
                  onChange={event => setStoreSettings(prev => ({
                    ...prev,
                    defaultReadyMinutes: Number(event.target.value),
                  }))}
                >
                  {READY_MINUTE_OPTIONS.map(minutes => (
                    <option key={minutes} value={minutes}>{minutes} min</option>
                  ))}
                </select>
              </div>
              <div className={`${styles.field} ${styles.fullWidth}`}>
                <label>Pickup orders</label>
                <div className={styles.toggleWrapper}>
                  <button
                    type="button"
                    className={`${styles.toggle} ${storeSettings.pickupOrdersEnabled ? styles.toggleOn : ''}`}
                    onClick={() => setStoreSettings(prev => ({
                      ...prev,
                      pickupOrdersEnabled: !prev.pickupOrdersEnabled,
                    }))}
                    aria-pressed={storeSettings.pickupOrdersEnabled}
                  />
                  <span>{storeSettings.pickupOrdersEnabled ? 'Accepting pickup orders' : 'Pickup orders paused'}</span>
                </div>
              </div>
            </div>

            <h3 style={{ marginTop: '1.75rem' }}>KDS access</h3>

            <div className={`${styles.field} ${styles.fullWidth}`}>
              <label>Allowed IP addresses ({kdsAllowedIps.length}/{MAX_KDS_ALLOWED_IPS})</label>
              {kdsAllowedIps.length === 0 ? (
                <p className={styles.kdsIpEmpty}>No IP addresses saved yet. Add one below, then click Save settings.</p>
              ) : (
                <ul className={styles.kdsIpList}>
                  {kdsAllowedIps.map(ip => {
                    const revealed = !!revealedKdsIps[ip];
                    return (
                      <li key={ip} className={styles.kdsIpRow}>
                        <code className={styles.kdsIpValue}>
                          {revealed ? ip : maskIpAddress(ip)}
                        </code>
                        <div className={styles.kdsIpActions}>
                          <button
                            type="button"
                            className={styles.kdsIpActionBtn}
                            onClick={() => setRevealedKdsIps(prev => ({
                              ...prev,
                              [ip]: !prev[ip],
                            }))}
                            aria-label={revealed ? `Hide ${ip}` : `Show ${ip}`}
                            title={revealed ? 'Hide IP' : 'Show IP'}
                          >
                            {revealed ? <EyeOff size={16} /> : <Eye size={16} />}
                          </button>
                          <button
                            type="button"
                            className={`${styles.kdsIpActionBtn} ${styles.kdsIpDeleteBtn}`}
                            onClick={() => removeKdsAllowedIp(ip)}
                            aria-label={`Delete ${ip}`}
                            title="Delete IP"
                          >
                            <Trash2 size={16} />
                          </button>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            {detectedClientIp && (
              <div className={styles.kdsDetectedIp}>
                <span>
                  This browser&apos;s IP (use this for local KDS):
                  <code>{detectedClientIp}</code>
                </span>
                <button
                  type="button"
                  className={styles.linkBtn}
                  disabled={
                    kdsAllowedIps.length >= MAX_KDS_ALLOWED_IPS
                    || kdsAllowedIps.some(ip => ip.toLowerCase() === detectedClientIp.toLowerCase())
                  }
                  onClick={() => {
                    setKdsIpDraft(detectedClientIp);
                    setKdsIpError('');
                    if (kdsAllowedIps.length >= MAX_KDS_ALLOWED_IPS) {
                      setKdsIpError(`You can add up to ${MAX_KDS_ALLOWED_IPS} IP addresses.`);
                      return;
                    }
                    if (kdsAllowedIps.some(ip => ip.toLowerCase() === detectedClientIp.toLowerCase())) {
                      setKdsIpError('That IP address is already on the list.');
                      return;
                    }
                    updateKdsAllowedIps([...kdsAllowedIps, detectedClientIp]);
                    setKdsIpDraft('');
                  }}
                >
                  Add this IP
                </button>
              </div>
            )}

            <div className={styles.kdsIpAddRow}>
              <div className={`${styles.field} ${styles.kdsIpAddField}`}>
                <label htmlFor="kdsAllowedIpDraft">Add IP address</label>
                <input
                  id="kdsAllowedIpDraft"
                  type="text"
                  value={kdsIpDraft}
                  onChange={event => {
                    setKdsIpDraft(event.target.value);
                    if (kdsIpError) {
                      setKdsIpError('');
                    }
                  }}
                  onKeyDown={event => {
                    if (event.key === 'Enter') {
                      event.preventDefault();
                      addKdsAllowedIp();
                    }
                  }}
                  placeholder="203.0.113.10 or 127.0.0.1"
                  disabled={kdsAllowedIps.length >= MAX_KDS_ALLOWED_IPS}
                />
              </div>
              <button
                type="button"
                className={styles.addBtn}
                onClick={addKdsAllowedIp}
                disabled={kdsAllowedIps.length >= MAX_KDS_ALLOWED_IPS}
              >
                Add IP
              </button>
            </div>
            {kdsIpError && <p className={styles.kdsIpError}>{kdsIpError}</p>}
            {kdsAllowedIps.length >= MAX_KDS_ALLOWED_IPS && (
              <small style={{ color: 'var(--text-light)' }}>
                Maximum of {MAX_KDS_ALLOWED_IPS} IP addresses reached. Delete one to add another.
              </small>
            )}

            <div className={styles.formActions}>
              <button type="submit" className={styles.saveBtn} disabled={settingsLoading || !settingsDirty}>
                {settingsLoading ? 'Saving...' : 'Save settings'}
              </button>
            </div>
          </form>
        </motion.div>
      )}

      {/* Most Loved Tab */}
      {activeTab === 'mostloved' && (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          <div className={styles.sectionHeader}>
            <h2>Manage Most Loved Items</h2>
            <p style={{ color: 'var(--text-light)', marginTop: '0.5rem' }}>Select items to feature in the &quot;Most Loved&quot; carousel on the homepage.</p>
          </div>
          <div className={styles.menuTable}>
            <div className={styles.tableHeader}>
              <span className={styles.colImage}>Image</span>
              <span className={styles.colName}>Name</span>
              <span className={styles.colCategory}>Category</span>
              <span className={styles.colPrice}>Price</span>
              <span className={styles.colActions}>Most Loved Status</span>
            </div>
            <AnimatePresence>
              {menuItems.map(item => (
                <motion.div
                  key={item.id}
                  className={`${styles.tableRow} ${!item.available ? styles.unavailable : ''}`}
                  layout
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 10 }}
                >
                  <span className={styles.colImage}>
                    <div className={styles.thumbnail}>
                      {item.image && item.image !== "" ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={backendAssetUrl(item.image)} alt={item.name} />
                      ) : (
                        <span className={styles.noImage}>🍽️</span>
                      )}
                    </div>
                  </span>
                  <span className={styles.colName}>
                    <strong>{item.name}</strong>
                    <small>{item.description.length > 60 ? item.description.substring(0, 60) + '...' : item.description}</small>
                  </span>
                  <span className={styles.colCategory}>
                    <span className={styles.categoryPill}>{item.category}</span>
                  </span>
                  <span className={styles.colPrice}>${item.price.toFixed(2)}</span>
                  <span className={styles.colActions}>
                    <button
                      className={`${styles.statusBadge} ${item.mostLoved ? styles.statusApproved : styles.statusPending}`}
                      onClick={() => toggleMostLoved(item.id)}
                      style={{ cursor: 'pointer', padding: '0.5rem 1rem' }}
                    >
                      {item.mostLoved ? '❤️ Featured' : '🤍 Select'}
                    </button>
                  </span>
                </motion.div>
              ))}
            </AnimatePresence>
            {menuItems.length === 0 && (
              <div className={styles.emptyState}>
                <p>No menu items available to feature.</p>
              </div>
            )}
          </div>
        </motion.div>
      )}
        </main>
      </div>

      {/* Confirm Modal */}
      <AnimatePresence>
        {confirmModal.isOpen && (
          <div className={styles.modalBackdrop}>
            <motion.div 
              className={`${styles.confirmModal} premium-card`}
              initial={{ opacity: 0, scale: 0.9, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.9, y: 20 }}
            >
              <div className={styles.modalIcon}>⚠️</div>
              <h3>{confirmModal.title}</h3>
              <p>{confirmModal.message}</p>
              <div className={styles.modalActions}>
                <button 
                  className={styles.cancelBtn} 
                  onClick={() => setConfirmModal(p => ({ ...p, isOpen: false }))}
                >
                  Cancel
                </button>
                <button 
                  className={styles.deleteConfirmBtn} 
                  onClick={confirmModal.onConfirm}
                >
                  Confirm Delete
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {selectedOrder && (
          <div className={styles.modalBackdrop} onClick={() => setSelectedOrder(null)}>
            <motion.div
              className={`${styles.orderDetailModal} premium-card`}
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={(e) => e.stopPropagation()}
            >
              <div className={styles.orderDetailHeader}>
                <div>
                  <span className={styles.detailEyebrow}>Order Details</span>
                  <h3>#{formatOrderId(selectedOrder.id)}</h3>
                </div>
                <button className={styles.cancelBtn} onClick={() => setSelectedOrder(null)}>Close</button>
              </div>

              <div className={styles.detailGrid}>
                <div><span>Order ID</span><strong>{selectedOrder.id}</strong></div>
                <div><span>Status</span><strong>{orderStatusLabel(selectedOrder.status)}</strong></div>
                <div><span>Name</span><strong>{selectedOrder.customerName}</strong></div>
                <div><span>Phone</span><strong>{selectedOrder.customerPhone}</strong></div>
                <div><span>Email</span><strong>{selectedOrder.customerEmail}</strong></div>
                <div><span>Pickup Date</span><strong>{selectedOrder.pickupDate}</strong></div>
                <div><span>Pickup Time</span><strong>{selectedOrder.pickupTime}</strong></div>
                <div><span>Average Ready Time</span><strong>{selectedOrder.averageReadyMinutes} min</strong></div>
                <div><span>Created</span><strong>{formatTime(selectedOrder.createdAt)}</strong></div>
                <div><span>Accepted</span><strong>{formatTime(selectedOrder.acceptedAt) || 'Not accepted'}</strong></div>
                <div><span>Ready</span><strong>{formatTime(selectedOrder.readyAt) || 'Not ready'}</strong></div>
                <div><span>Picked Up</span><strong>{formatTime(selectedOrder.pickedUpAt) || 'Not picked up'}</strong></div>
                {selectedOrder.status === 'NO_SHOW' && (
                  <div><span>No-show</span><strong>{formatTime(selectedOrder.noShowAt) || 'Marked no-show'}</strong></div>
                )}
              </div>

              <div className={styles.detailSection}>
                <h4>Items</h4>
                <div className={styles.detailItems}>
                  {selectedOrder.items.map((item, index) => (
                    <div key={`${selectedOrder.id}-${item.menuItemId}-${index}`}>
                      <span>{item.quantity}x {item.name}</span>
                      <strong>{formatMoney(item.total)}</strong>
                    </div>
                  ))}
                </div>
              </div>

              <div className={styles.detailTotals}>
                <div><span>Subtotal</span><strong>{formatMoney(selectedOrder.subtotal)}</strong></div>
                <div><span>Tax</span><strong>{formatMoney(selectedOrder.tax)}</strong></div>
                <div><span>Total</span><strong>{formatMoney(selectedOrder.total)}</strong></div>
              </div>

              <div className={styles.detailSection}>
                <h4>Messages</h4>
                <div className={styles.detailMessages}>
                  {selectedOrder.messages?.map(message => (
                    <div key={message.id}>
                      <span>{message.senderName} - {formatTime(message.createdAt)}</span>
                      <p>{message.text}</p>
                    </div>
                  ))}
                  {(!selectedOrder.messages || selectedOrder.messages.length === 0) && <p>No messages.</p>}
                </div>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
