'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import styles from './profile.module.css';
import { motion, AnimatePresence } from 'framer-motion';
import { Mail, Phone, Package, Clock, LogOut, User, Pencil, ShieldCheck, MonitorCog } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { io } from 'socket.io-client';
import type { Socket } from 'socket.io-client';
import { formatPhoneInput, getPhoneDigitCount } from '@/lib/phone';
import { persistAuth, clearAuth, getAuthHeaders } from '@/lib/auth';
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
  const [isLoggedIn, setIsLoggedIn] = useState<boolean | null>(null);
  const [showLogoutModal, setShowLogoutModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editStep, setEditStep] = useState<'form' | 'verify'>('form');
  const [editForm, setEditForm] = useState({ name: '', email: '', phone: '' });
  const [originalForm, setOriginalForm] = useState({ name: '', email: '', phone: '' });
  const [emailOtp, setEmailOtp] = useState('');
  const [phoneOtp, setPhoneOtp] = useState('');
  const [requiresEmailOtp, setRequiresEmailOtp] = useState(false);
  const [requiresPhoneOtp, setRequiresPhoneOtp] = useState(false);
  const [emailOtpSentTo, setEmailOtpSentTo] = useState('');
  const [phoneOtpSentTo, setPhoneOtpSentTo] = useState('');
  const [editError, setEditError] = useState('');
  const [editLoading, setEditLoading] = useState(false);
  const [orders, setOrders] = useState<CustomerOrder[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [ordersError, setOrdersError] = useState('');
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
      token: data.token as string | undefined,
      name: data.name as string,
      email: data.email as string,
      phone: (data.phone as string) || '',
      memberSince: (data.memberSince as string) || '',
      role: data.role as string | undefined,
    });
  };

  useEffect(() => {
    const verifySession = async () => {
      const token = localStorage.getItem('authToken');
      if (!token) {
        router.push('/login');
        return;
      }

      try {
        const res = await fetch(`${AUTH_API}/me`, {
          headers: { Authorization: `Bearer ${token}` },
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
      const res = await fetch(`${AUTH_API}/orders`, {
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

    const jwt = localStorage.getItem('authToken') || '';
    const sockets = activeOrders.map(order => {
      const socket = io(SOCKET_URL, {
        query: { orderId: order.id },
        auth: { token: jwt },
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
    const form = { name: user.name, email: user.email, phone: user.phone };
    setEditForm(form);
    setOriginalForm(form);
    setEditStep('form');
    setEmailOtp('');
    setPhoneOtp('');
    setRequiresEmailOtp(false);
    setRequiresPhoneOtp(false);
    setEditError('');
    setShowEditModal(true);
  };

  const closeEditModal = () => {
    setShowEditModal(false);
    setEditStep('form');
    setEditError('');
  };

  const emailChanged = editForm.email.trim().toLowerCase() !== originalForm.email.trim().toLowerCase();
  const phoneChanged = editForm.phone !== originalForm.phone;

  const handleSaveProfile = async () => {
    setEditError('');

    if (!user.canUpdateProfile) {
      setEditError(`You can update your profile again on ${user.nextProfileUpdateAt}.`);
      return;
    }

    if (!editForm.name.trim()) {
      setEditError('Name is required.');
      return;
    }

    if (getPhoneDigitCount(editForm.phone) !== 10) {
      setEditError('Please enter a valid 10-digit phone number.');
      return;
    }

    setEditLoading(true);
    const token = localStorage.getItem('authToken');

    try {
      if (emailChanged || phoneChanged) {
        const verifyRes = await fetch(`${AUTH_API}/profile/send-verification`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({ email: editForm.email.trim(), phone: editForm.phone }),
        });

        if (!verifyRes.ok) {
          const message = await verifyRes.text();
          setEditError(message || 'Failed to send verification codes.');
          return;
        }

        const verifyData = await verifyRes.json();
        if (verifyData.requiresEmailOtp || verifyData.requiresPhoneOtp) {
          setRequiresEmailOtp(verifyData.requiresEmailOtp);
          setRequiresPhoneOtp(verifyData.requiresPhoneOtp);
          setEmailOtpSentTo(verifyData.emailOtpSentTo || '');
          setPhoneOtpSentTo(verifyData.phoneOtpSentTo || '');
          setEditStep('verify');
          return;
        }
      }

      await submitProfileUpdate();
    } catch {
      setEditError('Unable to connect to the server. Please try again.');
    } finally {
      setEditLoading(false);
    }
  };

  const submitProfileUpdate = async () => {
    setEditLoading(true);
    setEditError('');
    const token = localStorage.getItem('authToken');

    try {
      const res = await fetch(`${AUTH_API}/profile`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          name: editForm.name.trim(),
          email: editForm.email.trim(),
          phone: editForm.phone,
          emailOtp: requiresEmailOtp ? emailOtp.trim() : undefined,
          phoneOtp: requiresPhoneOtp ? phoneOtp.trim() : undefined,
        }),
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

  const confirmLogout = () => {
    clearAuth();
    window.dispatchEvent(new Event('storage'));
    setShowLogoutModal(false);
    router.push('/login');
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
              <section className={styles.orderSection}>
                <h3>
                  <span className={styles.liveDot} aria-hidden />
                  Active orders ({activeOrders.length})
                </h3>
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

              <section className={styles.orderSection}>
                <h3>Past orders ({pastOrders.length})</h3>
                {pastOrders.length === 0 ? (
                  <p className={styles.sectionEmpty}>No completed orders yet.</p>
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
            </div>
          )}
        </motion.div>
      </div>

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
              <p className={styles.weeklyNotice}>
                You can update your profile information once a week.
                {!user.canUpdateProfile && user.nextProfileUpdateAt && (
                  <> Next update available on <strong>{user.nextProfileUpdateAt}</strong>.</>
                )}
              </p>

              {editError && <div className={styles.editError}>{editError}</div>}

              {editStep === 'form' ? (
                <div className={styles.editForm}>
                  <div className={styles.editField}>
                    <label><User size={16} /> Full Name</label>
                    <input
                      type="text"
                      value={editForm.name}
                      onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                      disabled={!user.canUpdateProfile || editLoading}
                    />
                  </div>
                  <div className={styles.editField}>
                    <label><Mail size={16} /> Email</label>
                    <input
                      type="email"
                      value={editForm.email}
                      onChange={(e) => setEditForm({ ...editForm, email: e.target.value })}
                      disabled={!user.canUpdateProfile || editLoading}
                    />
                  </div>
                  <div className={styles.editField}>
                    <label><Phone size={16} /> Phone</label>
                    <input
                      type="tel"
                      inputMode="numeric"
                      value={editForm.phone}
                      onChange={(e) => setEditForm({ ...editForm, phone: formatPhoneInput(e.target.value) })}
                      disabled={!user.canUpdateProfile || editLoading}
                    />
                  </div>
                </div>
              ) : (
                <div className={styles.verifySection}>
                  <p className={styles.verifyIntro}>
                    Enter the verification code{requiresEmailOtp && requiresPhoneOtp ? 's' : ''} sent to confirm your changes.
                  </p>
                  {requiresEmailOtp && (
                    <div className={styles.editField}>
                      <label>Email code sent to {emailOtpSentTo}</label>
                      <input
                        type="text"
                        inputMode="numeric"
                        maxLength={6}
                        placeholder="6-digit code"
                        value={emailOtp}
                        onChange={(e) => setEmailOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                        disabled={editLoading}
                      />
                    </div>
                  )}
                  {requiresPhoneOtp && (
                    <div className={styles.editField}>
                      <label>Phone change code sent to {phoneOtpSentTo}</label>
                      <input
                        type="text"
                        inputMode="numeric"
                        maxLength={6}
                        placeholder="6-digit code"
                        value={phoneOtp}
                        onChange={(e) => setPhoneOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                        disabled={editLoading}
                      />
                    </div>
                  )}
                </div>
              )}

              <div className={styles.modalActions}>
                <button className={styles.cancelBtn} onClick={closeEditModal} disabled={editLoading}>
                  Cancel
                </button>
                {editStep === 'form' ? (
                  <button
                    className={styles.saveBtn}
                    onClick={handleSaveProfile}
                    disabled={!user.canUpdateProfile || editLoading}
                  >
                    {editLoading ? 'Saving...' : 'Save'}
                  </button>
                ) : (
                  <button
                    className={styles.saveBtn}
                    onClick={submitProfileUpdate}
                    disabled={editLoading}
                  >
                    {editLoading ? 'Confirming...' : 'Confirm Save'}
                  </button>
                )}
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
