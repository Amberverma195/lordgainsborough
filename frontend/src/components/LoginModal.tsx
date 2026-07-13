'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { motion, AnimatePresence } from 'framer-motion';
import { LogIn, Mail, Lock, ArrowRight, X, Eye, EyeOff } from 'lucide-react';
import styles from './LoginModal.module.css';
import { persistAuth, type AuthData } from '@/lib/auth';
import { formatLoginIdentifier } from '@/lib/phone';
import { API_BASE } from '@/lib/orders';

interface LoginModalProps {
  open: boolean;
  onClose: () => void;
  onSuccess?: (data: AuthData) => void;
  title?: string;
  subtitle?: string;
}

export default function LoginModal({
  open,
  onClose,
  onSuccess,
  title = 'Sign in to continue',
  subtitle = 'Please log in to place your order.',
}: LoginModalProps) {
  const [emailOrPhone, setEmailOrPhone] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const res = await fetch(`${API_BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          emailOrPhone: emailOrPhone.trim(),
          email: emailOrPhone.trim(),
          password,
        }),
      });

      if (res.ok) {
        const data: AuthData = await res.json();
        persistAuth(data);
        window.dispatchEvent(new Event('storage'));
        setEmailOrPhone('');
        setPassword('');
        onSuccess?.(data);
        onClose();
        return;
      }

      const message = await res.text();
      setError(message || 'Something went wrong. Please try again.');
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className={styles.backdrop}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
        >
          <motion.div
            className={`${styles.modal} premium-card`}
            initial={{ opacity: 0, y: 24, scale: 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 16, scale: 0.96 }}
            transition={{ type: 'spring', bounce: 0, duration: 0.35 }}
            onClick={e => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="login-modal-title"
          >
            <button type="button" className={styles.closeBtn} onClick={onClose} aria-label="Close">
              <X size={18} />
            </button>

            <div className={styles.header}>
              <div className={styles.iconWrapper}>
                <LogIn size={28} />
              </div>
              <h2 id="login-modal-title">{title}</h2>
              <p>{subtitle}</p>
            </div>

            {error && <div className={styles.errorBanner}>{error}</div>}

            <form className={styles.form} onSubmit={handleLogin}>
              <div className={styles.inputGroup}>
                <label><Mail size={16} /> Email or Phone Number</label>
                <input
                  type="text"
                  inputMode="email"
                  autoComplete="username"
                  placeholder="Email or phone number"
                  value={emailOrPhone}
                  onChange={e => setEmailOrPhone(formatLoginIdentifier(e.target.value))}
                  required
                  autoFocus
                />
              </div>

              <div className={styles.inputGroup}>
                <label><Lock size={16} /> Password</label>
                <div className={styles.passwordField}>
                  <input
                    type={showPassword ? 'text' : 'password'}
                    placeholder="••••••••"
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    required
                  />
                  <button
                    type="button"
                    className={styles.passwordToggle}
                    onClick={() => setShowPassword(prev => !prev)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </div>

              <button type="submit" className={styles.loginBtn} disabled={loading}>
                {loading ? 'Authenticating...' : 'Sign In'} <ArrowRight size={18} />
              </button>
            </form>

            <p className={styles.footer}>
              Don&apos;t have an account? <Link href="/signup" onClick={onClose}>Sign up now</Link>
            </p>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
