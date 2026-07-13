'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { LogIn, Mail, Lock, ArrowRight, Eye, EyeOff } from 'lucide-react';
import styles from './login.module.css';
import { persistAuth } from '@/lib/auth';
import { formatLoginIdentifier } from '@/lib/phone';
import { API_BASE } from '@/lib/orders';

export default function LoginPage() {
  const [emailOrPhone, setEmailOrPhone] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const router = useRouter();

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
        const data = await res.json();
        persistAuth(data);
        window.dispatchEvent(new Event('storage'));
        router.push('/profile');
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
    <div className={styles.container}>
      <motion.div 
        className={`${styles.loginCard} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className={styles.header}>
          <div className={styles.iconWrapper}>
            <LogIn size={32} />
          </div>
          <h1>Welcome Back</h1>
          <p>Sign in to your Lord Gainsborough account</p>
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
              onChange={(e) => setEmailOrPhone(formatLoginIdentifier(e.target.value))}
              required 
            />
          </div>

          <div className={styles.inputGroup}>
            <label><Lock size={16} /> Password</label>
            <div className={styles.passwordField}>
              <input 
                type={showPassword ? 'text' : 'password'} 
                placeholder="•••••••••••" 
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required 
              />
              <button
                type="button"
                className={styles.passwordToggle}
                onClick={() => setShowPassword((prev) => !prev)}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </div>
          </div>

          <div className={styles.options}>
            <Link href="/forgot-password" className={styles.forgotPass}>
              Forgot password?
            </Link>
          </div>

          <button type="submit" className={styles.loginBtn} disabled={loading}>
            {loading ? 'Authenticating...' : 'Sign In'} <ArrowRight size={18} />
          </button>
        </form>

        <p className={styles.footer}>
          Don&apos;t have an account? <Link href="/signup">Sign up now</Link>
        </p>
      </motion.div>
    </div>
  );
}
