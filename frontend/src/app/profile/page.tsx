'use client';

import { apiFetch } from '@/lib/apiFetch';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import styles from './profile.module.css';
import { motion, AnimatePresence } from 'framer-motion';
import { Mail, Phone, Package, Clock, LogOut, User, Pencil, ShieldCheck, MonitorCog, Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { io } from 'socket.io-client';
import { orderSocketAuth } from '@/lib/orderSocketAuth';
import type { Socket } from 'socket.io-client';
import { persistAuth, clearAuth, getAuthHeaders, signOut } from '@/lib/auth';
import { useCart } from '@/context/CartContext';
import { clearDeletedAccountData } from '@/lib/accountDeletion';
import { canAccessKds, isAdminRole } from '@/lib/roles';
import {
  API_BASE,
  CustomerOrder,
  OrderStatus,
  SOCKET_URL,
  formatMoney,
  formatOrderId,
  formatPickupDateLabel,
  isActiveOrderStatus,
  orderStatusLabel,
  sortOrders,
} from '@/lib/orders';

const AUTH_API = `${API_BASE}/auth`;

function statusBadgeClass(status: OrderStatus): string {
  if (status === 'NEW') return styles.statusNew;
  if (status === 'ACCEPTED') return styles.statusAccepted;
  if (status === 'READY') return styles.statusReady;
  return styles.statusPickedUp;
}

interface UserData {
  name: string;
  email: string;
  phone: string;
  memberSince: string;
  role: string;
  canUpdateProfile: boolean;
  nextProfileUpdateAt: string | null;
}

export default function ProfilePage() {
  const router = useRouter();
  const { clearCart } = useCart();
  const deleteDialogRef = useRef<HTMLDialogElement>(null);
  const deleteButtonRef = useRef<HTMLButtonElement>(null);
  const deleteCancelRef = useRef<HTMLButtonElement>(null);
  const deletionInFlight = useRef(false);
  const [deletePassword, setDeletePassword] = useState('');
  const [deleteError, setDeleteError] = useState('');
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState<boolean | null>(null);
  const [showLogoutModal, setShowLogoutModal] = useState(false);
  const [logoutError, setLogoutError] = useState('');
  const [showEditModal, setShowEditModal] = useState(false);
  const [editName, setEditName] = useState('');
  const [editError, setEditError] = useState('');
  const [editLoading, setEditLoading] = useState(false);
  const [orders, setOrders] = useState<CustomerOrder[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [ordersError, setOrdersError] = useState('');
  const [orderTab, setOrderTab] = useState<'active' | 'past'>('active');
  const socketsRef = useRef<Socket[]>([]);
  const [user, setUser] = useState<UserData>({
    name: '',
    email: '',
    phone: '',
    memberSince: '',
    role: '',
    canUpdateProfile: true,
    nextProfileUpdateAt: null,
  });

  const applyUserData = (data: Record<string, unknown>) => {
    setUser({
      name: data.name as string,
      email: data.email as string,
      phone: (data.phone as string) || '',
      memberSince: (data.memberSince as string) || '',
      role: (data.role as string) || '',
      canUpdateProfile: data.canUpdateProfile !== false,
      nextProfileUpdateAt: (data.nextProfileUpdateAt as string) || null,
    });
    persistAuth({
      name: data.name as string,
      email: data.email as string,
      phone: (data.phone as string) || '',
      memberSince: (data.memberSince as string) || '',
      role: data.role as string | undefined,
      noShowCount: typeof data.noShowCount === 'number' ? data.noShowCount : undefined,
      pickupDisabled: Boolean(data.pickupDisabled),
    });
  };

  useEffect(() => {
    const verifySession = async () => {
      try {
        const res = await apiFetch(`${AUTH_API}/me`, {
          headers: getAuthHeaders(false),
        });

        if (res.ok) {
          const data = await res.json();
          applyUserData(data);
          setIsLoggedIn(true);
          return;
        }

        clearAuth();
        router.push('/login');
      } catch {
        setIsLoggedIn(false);
        router.push('/login');
      }
    };

    verifySession();
  }, [router]);

  const fetchOrders = useCallback(async (showLoading = true) => {
    if (showLoading) {
      setOrdersLoading(true);
    }
    setOrdersError('');
    try {
      const res = await apiFetch(`${AUTH_API}/orders`, {
        headers: getAuthHeaders(false),
      });
      if (!res.ok) {
        throw new Error('Failed to load orders');
      }
      const data = await res.json();
      setOrders(sortOrders(Array.isArray(data) ? data : []));
    } catch {
      setOrdersError('Could not load your order history.');
    } finally {
      if (showLoading) {
        setOrdersLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    if (isLoggedIn === true) {
      const timeout = window.setTimeout(() => fetchOrders(), 0);
      return () => window.clearTimeout(timeout);
    }
  }, [fetchOrders, isLoggedIn]);

  const activeOrders = useMemo(
    () => orders.filter(order => isActiveOrderStatus(order.status)),
    [orders],
  );
  const pastOrders = useMemo(
    () => orders.filter(order => !isActiveOrderStatus(order.status)),
    [orders],
  );

  useEffect(() => {
    if (isLoggedIn !== true || activeOrders.length === 0) {
      socketsRef.current.forEach(socket => socket.disconnect());
      socketsRef.current = [];
      return;
    }

    const sockets = activeOrders.map(order => {
      const socket = io(SOCKET_URL, {
        query: { orderId: order.id },
        auth: orderSocketAuth(order.id),
        transports: ['websocket', 'polling'],
      });
      socket.on('order.updated', (updated: CustomerOrder) => {
        if (updated.id !== order.id) {
          return;
        }
        setOrders(prev => sortOrders(
          prev.map(item => (item.id === updated.id ? updated : item)),
        ));
      });
      return socket;
    });

    socketsRef.current = sockets;

    const poll = window.setInterval(() => fetchOrders(false), 30000);

    return () => {
      window.clearInterval(poll);
      sockets.forEach(socket => socket.disconnect());
      socketsRef.current = [];
    };
  }, [activeOrders, fetchOrders, isLoggedIn]);

  const openEditModal = () => {
    setEditName(user.name);
    setEditError('');
    setShowEditModal(true);
  };

  const closeEditModal = () => {
    setShowEditModal(false);
    setEditError('');
  };

  const handleSaveProfile = async () => {
    setEditError('');

    if (!editName.trim()) {
      setEditError('Name is required.');
      return;
    }

    setEditLoading(true);

    try {
      const res = await apiFetch(`${AUTH_API}/profile`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ name: editName.trim() }),
      });

      if (res.ok) {
        const data = await res.json();
        applyUserData(data);
        window.dispatchEvent(new Event('storage'));
        closeEditModal();
        return;
      }

      const message = await res.text();
      setEditError(message || 'Failed to update profile.');
    } catch {
      setEditError('Unable to connect to the server. Please try again.');
    } finally {
      setEditLoading(false);
    }
  };

  const confirmLogout = async () => {
    try { await signOut(); } catch { setLogoutError('Could not sign out. Please try again.'); return; }
    window.dispatchEvent(new Event('storage'));
    setShowLogoutModal(false);
    router.push('/login');
  };

  const closeDeleteDialog = () => {
    if (deletionInFlight.current) return;
    deleteDialogRef.current?.close();
    setDeletePassword('');
    setDeleteError('');
    deleteButtonRef.current?.focus();
  };

  const handleDeleteAccount = async (event: React.FormEvent) => {
    event.preventDefault();
    if (deletionInFlight.current) return;
    deletionInFlight.current = true;
    setDeleteLoading(true);
    setDeleteError('');
    try {
      const response = await apiFetch(`${AUTH_API}/me`, {
        method: 'DELETE',
        headers: getAuthHeaders(),
        body: JSON.stringify({ password: deletePassword }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        setDeleteError(body?.message || 'Could not delete your account. Please try again.');
        return;
      }
      socketsRef.current.forEach(socket => socket.disconnect());
      setDeletePassword('');
      clearDeletedAccountData();
      // Authentication is cleared first, so this only clears the browser cart.
      clearCart();
      window.location.replace('/login?accountDeleted=1');
    } catch {
      setDeleteError('Unable to connect to the server. Please try again.');
    } finally {
      deletionInFlight.current = false;
      setDeleteLoading(false);
    }
  };

  if (isLoggedIn === null || isLoggedIn === false) {
    return (
      <div className={styles.spinnerContainer}>
        <div className={styles.spinnerWrapper}>
          <div className={styles.spinner}></div>
          <p style={{ fontSize: '1.2rem' }}>Verifying your session...</p>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <motion.div 
        className={styles.header}
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1 className={styles.title}>My Account</h1>
        <p className={styles.subtitle}>Manage your profile and view order history</p>
      </motion.div>

      <div className={styles.contentGrid}>
        <motion.div 
          className={`${styles.card} premium-card`}
          initial={{ opacity: 0, x: -20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: 0.1 }}
        >
          <div className={styles.profileHeader}>
            <h2>{user.name}</h2>
          </div>

          <div className={styles.infoList}>
            <div className={styles.infoItem}>
              <Mail className={styles.infoIcon} size={18} />
              <span>{user.email}</span>
            </div>
            <div className={styles.infoItem}>
              <Phone className={styles.infoIcon} size={18} />
              <span>{user.phone}</span>
            </div>
            <div className={styles.infoItem}>
              <Clock className={styles.infoIcon} size={18} />
              <span>Member since {user.memberSince}</span>
            </div>
          </div>

          <div className={styles.cardActions}>
            {canAccessKds(user.role) && (
              <Link href="/kds" className={styles.adminBtn}>
                <MonitorCog size={16} /> Kitchen Display
              </Link>
            )}
            {isAdminRole(user.role) && (
              <Link href="/admin" className={styles.adminBtn}>
                <ShieldCheck size={16} /> Admin Panel
              </Link>
            )}
            <button className={styles.editBtn} onClick={openEditModal}>
              <Pencil size={16} /> Edit Profile
            </button>
            <button className={styles.logoutBtn} onClick={() => setShowLogoutModal(true)}>
              <LogOut size={16} /> Logout
            </button>
            <button ref={deleteButtonRef} className={styles.deleteAccountBtn} onClick={() => {
              deleteDialogRef.current?.showModal();
              deleteCancelRef.current?.focus();
            }}>
              <Trash2 size={16} /> Delete account
            </button>
          </div>
        </motion.div>

        <motion.div 
          className={`${styles.card} ${styles.ordersCard} premium-card`}
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: 0.2 }}
        >
          <div className={styles.cardHeader}>
            <Package className={styles.headerIcon} />
            <h2>Order History</h2>
          </div>

          {ordersLoading && (
            <div className={styles.ordersLoading}>Loading orders...</div>
          )}

          {!ordersLoading && ordersError && (
            <div className={styles.ordersError}>{ordersError}</div>
          )}

          {!ordersLoading && !ordersError && orders.length === 0 && (
            <div className={styles.emptyOrders}>
              <Package className={styles.emptyIcon} size={48} />
              <p>No orders yet</p>
              <Link href="/menu" className={styles.orderNowBtn}>
                Order Now
              </Link>
            </div>
          )}

          {!ordersLoading && !ordersError && orders.length > 0 && (
            <div className={styles.orderSections}>
              <div className={styles.orderTabs} role="tablist" aria-label="Order history">
                <button
                  type="button"
                  role="tab"
                  aria-selected={orderTab === 'active'}
                  className={`${styles.orderTab} ${orderTab === 'active' ? styles.orderTabActive : ''}`}
                  onClick={() => setOrderTab('active')}
                >
                  {orderTab === 'active' && <span className={styles.liveDot} aria-hidden />}
                  Active orders ({activeOrders.length})
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={orderTab === 'past'}
                  className={`${styles.orderTab} ${orderTab === 'past' ? styles.orderTabActive : ''}`}
                  onClick={() => setOrderTab('past')}
                >
                  Past orders ({pastOrders.length})
                </button>
              </div>

              {orderTab === 'active' ? (
                <section className={styles.orderSection} role="tabpanel">
                  {activeOrders.length === 0 ? (
                    <p className={styles.sectionEmpty}>No active orders right now.</p>
                  ) : (
                    <ul className={styles.orderList}>
                      {activeOrders.map(order => (
                        <li className={styles.orderCard} key={order.id}>
                          <div className={styles.orderCardTop}>
                            <div>
                              <p className={styles.orderId}>#{formatOrderId(order.id)}</p>
                              <p className={styles.orderMeta}>
                                Pickup {formatPickupDateLabel(order.pickupDate)} · {order.pickupTime}
                              </p>
                            </div>
                            <span className={`${styles.statusBadge} ${statusBadgeClass(order.status)}`}>
                              {orderStatusLabel(order.status)}
                            </span>
                          </div>
                          <ul className={styles.orderItemsPreview}>
                            {order.items.slice(0, 3).map((item, index) => (
                              <li key={`${order.id}-${item.menuItemId}-${index}`}>
                                <span>{item.quantity}× {item.name}</span>
                                <span>{formatMoney(item.total)}</span>
                              </li>
                            ))}
                            {order.items.length > 3 && (
                              <li><span>+{order.items.length - 3} more items</span></li>
                            )}
                          </ul>
                          <div className={styles.orderCardFooter}>
                            <span className={styles.orderTotal}>{formatMoney(order.total)}</span>
                            <Link href={`/orders/${order.id}`} className={styles.trackLink}>
                              Track live
                            </Link>
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              ) : (
                <section className={styles.orderSection} role="tabpanel">
                  {pastOrders.length === 0 ? (
                    <p className={styles.sectionEmpty}>No past orders</p>
                  ) : (
                    <ul className={styles.orderList}>
                      {pastOrders.map(order => (
                        <li className={styles.orderCard} key={order.id}>
                          <div className={styles.orderCardTop}>
                            <div>
                              <p className={styles.orderId}>#{formatOrderId(order.id)}</p>
                              <p className={styles.orderMeta}>
                                Pickup {formatPickupDateLabel(order.pickupDate)} · {order.pickupTime}
                              </p>
                            </div>
                            <span className={`${styles.statusBadge} ${statusBadgeClass(order.status)}`}>
                              {orderStatusLabel(order.status)}
                            </span>
                          </div>
                          <ul className={styles.orderItemsPreview}>
                            {order.items.slice(0, 3).map((item, index) => (
                              <li key={`${order.id}-${item.menuItemId}-${index}`}>
                                <span>{item.quantity}× {item.name}</span>
                                <span>{formatMoney(item.total)}</span>
                              </li>
                            ))}
                            {order.items.length > 3 && (
                              <li><span>+{order.items.length - 3} more items</span></li>
                            )}
                          </ul>
                          <div className={styles.orderCardFooter}>
                            <span className={styles.orderTotal}>{formatMoney(order.total)}</span>
                            <Link href={`/orders/${order.id}`} className={styles.trackLink}>
                              View details
                            </Link>
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}
            </div>
          )}
        </motion.div>
      </div>

      <dialog ref={deleteDialogRef} className={styles.deleteDialog} aria-labelledby="delete-account-title"
        aria-describedby="delete-account-description" onCancel={event => { event.preventDefault(); closeDeleteDialog(); }}>
        <form onSubmit={handleDeleteAccount}>
          <h3 id="delete-account-title">Delete your account?</h3>
          <p id="delete-account-description">This permanently deletes your account, saved cart, and all orders, including active orders, messages, and reviews. This cannot be undone.</p>
          <div className={styles.editField}>
            <label htmlFor="delete-password">Confirm your current password</label>
            <input id="delete-password" type="password" autoComplete="current-password" required maxLength={128}
              value={deletePassword} onChange={event => setDeletePassword(event.target.value)} disabled={deleteLoading} />
          </div>
          {deleteError && <p className={styles.editError} role="alert">{deleteError}</p>}
          <div className={styles.modalActions}>
            <button ref={deleteCancelRef} type="button" className={styles.cancelBtn} onClick={closeDeleteDialog} disabled={deleteLoading}>Cancel</button>
            <button type="submit" className={styles.confirmDeleteBtn} disabled={deleteLoading || !deletePassword.trim()}>
              {deleteLoading ? 'Deleting...' : 'Permanently delete account'}
            </button>
          </div>
        </form>
      </dialog>

      <AnimatePresence>
        {showEditModal && (
          <motion.div
            className={styles.modalBackdrop}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={closeEditModal}
          >
            <motion.div
              className={`${styles.editModal} premium-card`}
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={(e) => e.stopPropagation()}
            >
              <h3>Edit Profile</h3>

              {editError && <div className={styles.editError}>{editError}</div>}

              <div className={styles.editForm}>
                <div className={styles.editField}>
                  <label><User size={16} /> Full Name</label>
                  <input
                    type="text"
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    disabled={editLoading}
                    autoFocus
                  />
                </div>
              </div>

              <div className={styles.modalActions}>
                <button className={styles.cancelBtn} onClick={closeEditModal} disabled={editLoading}>
                  Cancel
                </button>
                <button
                  className={styles.saveBtn}
                  onClick={handleSaveProfile}
                  disabled={editLoading}
                >
                  {editLoading ? 'Saving...' : 'Save'}
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}

        {showLogoutModal && (
          <motion.div
            className={styles.modalBackdrop}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setShowLogoutModal(false)}
          >
            <motion.div
              className={`${styles.confirmModal} premium-card`}
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={(e) => e.stopPropagation()}
            >
              <div className={styles.modalIcon}>
                <LogOut size={32} />
              </div>
              <h3>Log Out?</h3>
              {logoutError && <p role="alert">{logoutError}</p>}
              <p>Are you sure you want to log out of your account?</p>
              <div className={styles.modalActions}>
                <button className={styles.cancelBtn} onClick={() => setShowLogoutModal(false)}>
                  Cancel
                </button>
                <button className={styles.confirmLogoutBtn} onClick={confirmLogout}>
                  Log Out
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
