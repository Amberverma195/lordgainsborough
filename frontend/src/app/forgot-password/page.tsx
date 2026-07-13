'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { motion } from 'framer-motion';
import { ArrowLeft, ArrowRight, KeyRound, Lock, Mail, ShieldCheck } from 'lucide-react';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';
import { API_BASE } from '@/lib/orders';
import {
  getPasswordValidationMessage,
  passwordsMatch,
  sanitizePasswordInput,
  validatePassword,
} from '@/lib/password';
import styles from './forgot-password.module.css';

type ResetStep = 'email' | 'reset' | 'complete';

function sanitizeEmailInput(value: string): string {
  return value
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, '')
    .replace(/[\u200B-\u200D\uFEFF\u202A-\u202E]/g, '')
    .trimStart()
    .slice(0, 254);
}

export default function ForgotPasswordPage() {
  const [step, setStep] = useState<ResetStep>('email');
  const [email, setEmail] = useState('');
  const [otp, setOtp] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const normalizedEmail = email.trim().toLowerCase();

  const sendResetOtp = async (fromResend = false) => {
    if (!normalizedEmail) {
      setError('Email is required.');
      return;
    }

    if (fromResend) {
      setResending(true);
    } else {
      setLoading(true);
    }
    setError('');
    setSuccess('');

    try {
      const res = await fetch(`${API_BASE}/auth/forgot-password/send-otp`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: normalizedEmail }),
      });

      const message = await res.text();
      if (res.ok) {
        setStep('reset');
        setSuccess(message || `Reset code sent to ${normalizedEmail}.`);
        return;
      }

      if (res.status === 404) {
        setError(message || 'No account exists with this email.');
      } else {
        setError(message || 'Failed to send reset code. Please try again.');
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
      setResending(false);
    }
  };

  const handleSendOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    await sendResetOtp();
  };

  const handleResetPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSuccess('');

    if (otp.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }

    const passwordError = getPasswordValidationMessage(password, confirmPassword);
    if (passwordError) {
      setError(passwordError);
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/forgot-password/reset`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: normalizedEmail,
          otp: otp.replace(/\D/g, '').slice(0, 6),
          password: sanitizePasswordInput(password),
        }),
      });

      const message = await res.text();
      if (res.ok) {
        setStep('complete');
        setOtp('');
        setPassword('');
        setConfirmPassword('');
        setSuccess(message || 'Password reset successfully.');
        return;
      }

      setError(message || 'Unable to reset password. Please try again.');
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const passwordValidation = validatePassword(password);
  const confirmMismatch = confirmPassword.length > 0 && !passwordsMatch(password, confirmPassword);
  const canReset =
    otp.length === 6 &&
    passwordValidation.valid &&
    passwordsMatch(password, confirmPassword) &&
    confirmPassword.length > 0;

  return (
    <div className={styles.container}>
      <motion.div
        className={`${styles.resetCard} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className={styles.header}>
          <div className={styles.iconWrapper}>
            {step === 'complete' ? <ShieldCheck size={32} /> : <KeyRound size={32} />}
          </div>
          <h1>{step === 'complete' ? 'Password Updated' : 'Reset Password'}</h1>
          <p>
            {step === 'email' && 'Enter your account email to receive a reset code'}
            {step === 'reset' && 'Enter the code from your email and choose a new password'}
            {step === 'complete' && 'Your account password has been changed'}
          </p>
        </div>

        {error && <div className={styles.errorBanner}>{error}</div>}
        {success && <div className={styles.successBanner}>{success}</div>}

        {step === 'email' && (
          <form className={styles.form} onSubmit={handleSendOtp}>
            <div className={styles.inputGroup}>
              <label><Mail size={16} /> Email Address</label>
              <input
                type="email"
                placeholder="Enter email address"
                value={email}
                onChange={(e) => setEmail(sanitizeEmailInput(e.target.value))}
                onBlur={() => setEmail(normalizedEmail)}
                disabled={loading}
                required
              />
            </div>

            <button type="submit" className={styles.primaryBtn} disabled={loading}>
              {loading ? 'Sending Code...' : 'Send Reset Code'} <ArrowRight size={18} />
            </button>
          </form>
        )}

        {step === 'reset' && (
          <form className={styles.form} onSubmit={handleResetPassword}>
            <div className={styles.inputGroup}>
              <label><ShieldCheck size={16} /> Verification Code</label>
              <input
                type="text"
                inputMode="numeric"
                maxLength={6}
                placeholder="6-digit code"
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                pattern="[0-9]{6}"
                disabled={loading}
                required
              />
            </div>

            <div className={styles.inputGroup}>
              <label><Lock size={16} /> New Password</label>
              <input
                type="password"
                placeholder="Enter new password"
                value={password}
                onChange={(e) => setPassword(sanitizePasswordInput(e.target.value))}
                maxLength={128}
                autoComplete="new-password"
                aria-invalid={password.length > 0 && !passwordValidation.valid}
                className={password.length > 0 && !passwordValidation.valid ? styles.inputInvalid : undefined}
                disabled={loading}
                required
              />
            </div>

            <div className={styles.inputGroup}>
              <label><Lock size={16} /> Confirm Password</label>
              <input
                type="password"
                placeholder="Re-enter new password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(sanitizePasswordInput(e.target.value))}
                maxLength={128}
                autoComplete="new-password"
                aria-invalid={confirmMismatch}
                className={confirmMismatch ? styles.inputInvalid : undefined}
                disabled={loading}
                required
              />
            </div>

            <PasswordStrengthIndicator password={password} confirmPassword={confirmPassword} />

            <button type="submit" className={styles.primaryBtn} disabled={loading || !canReset}>
              {loading ? 'Resetting...' : 'Reset Password'} <ArrowRight size={18} />
            </button>
          </form>
        )}

        {step === 'complete' && (
          <Link href="/login" className={styles.primaryLink}>
            Back to Sign In <ArrowRight size={18} />
          </Link>
        )}

        <div className={styles.actions}>
          {step === 'reset' && (
            <button
              type="button"
              className={styles.textBtn}
              onClick={() => sendResetOtp(true)}
              disabled={resending || loading}
            >
              {resending ? 'Sending...' : 'Resend Code'}
            </button>
          )}
          <Link href="/login" className={styles.backLink}>
            <ArrowLeft size={16} /> Back to Sign In
          </Link>
        </div>
      </motion.div>
    </div>
  );
}
