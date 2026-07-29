'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { UserPlus, Mail, Lock, User, ArrowRight, Phone } from 'lucide-react';
import { formatPhoneInput, getPhoneDigitCount } from '@/lib/phone';
import {
  validatePassword,
  passwordsMatch,
  getPasswordValidationMessage,
} from '@/lib/password';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';
import { API_BASE } from '@/lib/orders';
import {
  applyBackendChallengeState,
  clearAllSignupSession,
  fetchWithIdempotentRetry,
  readSignupOtpResponse,
  savePendingSignupCredentials,
  saveSignupOtpChallengeState,
} from '@/lib/signupOtp';
import { subscribeSignupOtpCrossTab, toCrossTabPayload } from '@/lib/signupOtpCrossTab';
import styles from './signup.module.css';

export default function SignupPage() {
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    phone: '',
    password: '',
    confirmPassword: ''
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [showPasswordMatchError, setShowPasswordMatchError] = useState(false);
  const router = useRouter();

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setShowPasswordMatchError(true);
    clearAllSignupSession();

    const passwordError = getPasswordValidationMessage(formData.password, formData.confirmPassword);
    if (passwordError) {
      setError(passwordError);
      return;
    }

    if (getPhoneDigitCount(formData.phone) !== 10) {
      setError('Please enter a valid 10-digit phone number');
      return;
    }

    setLoading(true);
    try {
      clearAllSignupSession();
      const idempotencyKey = crypto.randomUUID();
      const email = formData.email.trim().toLowerCase();
      const payload = {
        email,
        phone: formData.phone,
      };

      const res = await fetchWithIdempotentRetry((key) =>
        fetch(`${API_BASE}/auth/signup/send-otp`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify(payload),
        }), idempotencyKey);

      if (res.ok || res.status === 202) {
        const body = await readSignupOtpResponse(res);
        const challengeState = applyBackendChallengeState(null, body, email);
        saveSignupOtpChallengeState(challengeState);
        savePendingSignupCredentials({
          name: formData.name.trim(),
          email,
          phone: formData.phone,
        });
        // Notify other tabs that already have the verify page open.
        const sync = subscribeSignupOtpCrossTab(() => {});
        sync.publish(toCrossTabPayload(challengeState, 'challenge-updated', sync.tabId));
        sync.close();
        router.push('/signup/verify');
        return;
      }

      const body = await readSignupOtpResponse(res);
      setError(body.message || 'Something went wrong. Please try again.');
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFormData(prev => {
      if (name === 'password' || name === 'confirmPassword') {
        return { ...prev, [name]: value.slice(0, 128) };
      }
      return { ...prev, [name]: value };
    });
  };

  const handlePhoneChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFormData(prev => ({ ...prev, phone: formatPhoneInput(e.target.value) }));
  };

  const passwordValidation = validatePassword(formData.password);
  const confirmMismatch =
    showPasswordMatchError &&
    formData.confirmPassword.length > 0 &&
    !passwordsMatch(formData.password, formData.confirmPassword);
  const canSubmit =
    passwordValidation.valid &&
    formData.password.length > 0 &&
    formData.confirmPassword.length > 0;

  return (
    <div className={styles.container}>
      <motion.div 
        className={`${styles.signupCard} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className={styles.header}>
          <div className={styles.iconWrapper}>
            <UserPlus size={32} />
          </div>
          <h1>Join the Club</h1>
          <p>Create your account for exclusive dining experiences</p>
        </div>

        {error && <div className={styles.errorBanner}>{error}</div>}

        <form className={styles.form} onSubmit={handleSignup}>
          <div className={styles.inputRow}>
            <div className={styles.inputGroup}>
              <label><User size={16} /> Full Name</label>
              <input 
                name="name"
                type="text" 
                placeholder="Enter your full name" 
                value={formData.name}
                onChange={handleChange}
                required 
              />
            </div>
            <div className={styles.inputGroup}>
              <label><Phone size={16} /> Phone</label>
              <input 
                name="phone"
                type="tel"
                inputMode="numeric"
                placeholder="+1 (123)-456-7890"
                value={formData.phone}
                onChange={handlePhoneChange}
                required 
              />
            </div>
          </div>

          <div className={styles.inputGroup}>
            <label><Mail size={16} /> Email Address</label>
            <input 
              name="email"
              type="email" 
              placeholder="Enter email address" 
              value={formData.email}
              onChange={handleChange}
              required 
            />
          </div>

          <div className={styles.passwordSection}>
            <div className={styles.inputRow}>
              <div className={styles.inputGroup}>
                <label><Lock size={16} /> Password</label>
                <input
                  name="password"
                  type="password"
                  placeholder="Enter Password"
                  value={formData.password}
                  onChange={handleChange}
                  maxLength={128}
                  autoComplete="new-password"
                  aria-invalid={formData.password.length > 0 && !passwordValidation.valid}
                  className={
                    formData.password.length > 0 && !passwordValidation.valid
                      ? styles.inputInvalid
                      : undefined
                  }
                  required
                />
              </div>
              <div className={styles.inputGroup}>
                <label><Lock size={16} /> Confirm</label>
                <input
                  name="confirmPassword"
                  type="password"
                  placeholder="Re-enter password"
                  value={formData.confirmPassword}
                  onChange={handleChange}
                  maxLength={128}
                  autoComplete="new-password"
                  aria-invalid={confirmMismatch}
                  className={confirmMismatch ? styles.inputInvalid : undefined}
                  required
                />
              </div>
            </div>
            <PasswordStrengthIndicator
              password={formData.password}
              confirmPassword={formData.confirmPassword}
              showMatchError={showPasswordMatchError}
            />
          </div>

          <div className={styles.terms}>
            <label className={styles.checkboxLabel}>
              <input type="checkbox" required />
              <span>I agree to the <Link href="/terms">Terms of Service</Link> and <Link href="/privacy">Privacy Policy</Link></span>
            </label>
          </div>

          <button type="submit" className={styles.signupBtn} disabled={loading || !canSubmit}>
            {loading ? 'Sending Code...' : 'Create Account'} <ArrowRight size={18} />
          </button>
        </form>

        <p className={styles.footer}>
          Already have an account? <Link href="/login">Sign in here</Link>
        </p>
      </motion.div>
    </div>
  );
}
