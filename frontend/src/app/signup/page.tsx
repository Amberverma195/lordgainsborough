'use client';

import React, { useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { UserPlus, Mail, Lock, User, ArrowRight, Phone } from 'lucide-react';
import { formatPhoneInput, isValidPhoneNumber } from '@/lib/phone';
import { isValidEmail } from '@/lib/email';
import {
  validatePassword,
  passwordsMatch,
} from '@/lib/password';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';
import { API_BASE } from '@/lib/orders';
import {
  applyBackendChallengeState,
  clearAllSignupSession,
  fetchWithIdempotentRetry,
  loadSignupOtpChallengeState,
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
    confirmPassword: '',
  });
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [conflictPending, setConflictPending] = useState(false);
  const router = useRouter();

  const nameRef = useRef<HTMLInputElement>(null);
  const phoneRef = useRef<HTMLInputElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const confirmPasswordRef = useRef<HTMLInputElement>(null);
  const termsRef = useRef<HTMLInputElement>(null);

  const focusAndScrollTo = (ref: React.RefObject<HTMLInputElement | null>) => {
    if (ref.current) {
      ref.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
      ref.current.focus({ preventScroll: true });
    }
  };

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    const newErrors: Record<string, string> = {};
    let firstInvalidRef: React.RefObject<HTMLInputElement | null> | null = null;

    if (!formData.name.trim()) {
      newErrors.name = 'Please enter your full name.';
      if (!firstInvalidRef) firstInvalidRef = nameRef;
    }

    if (!isValidPhoneNumber(formData.phone)) {
      newErrors.phone = 'Please enter a valid 10-digit phone number.';
      if (!firstInvalidRef) firstInvalidRef = phoneRef;
    }

    if (!isValidEmail(formData.email)) {
      newErrors.email = 'Please enter a valid email address.';
      if (!firstInvalidRef) firstInvalidRef = emailRef;
    }

    const passwordValidation = validatePassword(formData.password);
    if (!passwordValidation.valid) {
      newErrors.password = 'Password must meet all complexity requirements.';
      if (!firstInvalidRef) firstInvalidRef = passwordRef;
    }

    if (!formData.confirmPassword || !passwordsMatch(formData.password, formData.confirmPassword)) {
      newErrors.confirmPassword = 'Passwords do not match.';
      if (!firstInvalidRef) firstInvalidRef = confirmPasswordRef;
    }

    if (!agreedToTerms) {
      newErrors.terms = 'Please agree to the Terms of Service and Privacy Policy to continue.';
      if (!firstInvalidRef) firstInvalidRef = termsRef;
    }

    if (firstInvalidRef) {
      setFieldErrors(newErrors);
      focusAndScrollTo(firstInvalidRef);
      return;
    }

    setFieldErrors({});
    setConflictPending(false);
    setLoading(true);

    try {
      const cleanEmail = formData.email.trim().toLowerCase();
      const existingChallenge = loadSignupOtpChallengeState();
      const existingToken = (existingChallenge && existingChallenge.email.toLowerCase() === cleanEmail)
        ? existingChallenge.attemptToken
        : undefined;

      const idempotencyKey = crypto.randomUUID();

      const payload = {
        name: formData.name.trim(),
        email: cleanEmail,
        phone: formData.phone,
        password: formData.password,
        attemptToken: existingToken,
        restart: Boolean(existingToken),
      };

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      };
      if (existingToken) {
        headers['X-Signup-Attempt-Token'] = existingToken;
      }

      const res = await fetchWithIdempotentRetry((key) =>
        fetch(`${API_BASE}/auth/signup/send-otp`, {
          method: 'POST',
          headers: {
            ...headers,
            'Idempotency-Key': key,
          },
          body: JSON.stringify(payload),
        }), idempotencyKey);

      if (res.ok || res.status === 202) {
        const body = await readSignupOtpResponse(res);
        clearAllSignupSession();
        const challengeState = applyBackendChallengeState(existingChallenge, body, cleanEmail);
        saveSignupOtpChallengeState(challengeState);
        savePendingSignupCredentials({
          name: formData.name.trim(),
          email: cleanEmail,
          phone: formData.phone,
        });

        const sync = subscribeSignupOtpCrossTab(() => {});
        sync.publish(toCrossTabPayload(challengeState, 'challenge-updated', sync.tabId));
        sync.close();

        router.push('/signup/verify');
        return;
      }

      const body = await readSignupOtpResponse(res);
      if (res.status === 409 && body.code === 'OTP_ATTEMPT_CONFLICT') {
        setConflictPending(true);
      }
      setError(body.message || 'Something went wrong. Please try again.');
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFieldErrors(prev => ({ ...prev, [name]: '' }));
    setFormData(prev => {
      if (name === 'password' || name === 'confirmPassword') {
        return { ...prev, [name]: value.slice(0, 128) };
      }
      return { ...prev, [name]: value };
    });
  };

  const handlePhoneChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFieldErrors(prev => ({ ...prev, phone: '' }));
    setFormData(prev => ({ ...prev, phone: formatPhoneInput(e.target.value) }));
  };

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
        {conflictPending && (
          <div style={{
            background: 'rgba(217, 119, 6, 0.1)',
            border: '1px solid rgba(217, 119, 6, 0.3)',
            borderRadius: '8px',
            padding: '1rem',
            marginBottom: '1.25rem',
            color: '#fbbf24',
            fontSize: '0.9rem',
            textAlign: 'center'
          }}>
            <p style={{ margin: '0 0 0.75rem 0' }}>
              A signup verification session is already in progress for this email address.
            </p>
            <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'center' }}>
              <button
                type="button"
                onClick={() => router.push('/signup/verify')}
                className="btn-secondary"
                style={{ padding: '0.4rem 0.8rem', fontSize: '0.85rem' }}
              >
                Enter Verification Code
              </button>
            </div>
          </div>
        )}

        <form className={styles.form} onSubmit={handleSignup} noValidate>
          <div className={styles.inputRow}>
            <div className={styles.inputGroup}>
              <label htmlFor="name-input"><User size={16} /> Full Name</label>
              <input
                id="name-input"
                ref={nameRef}
                name="name"
                type="text"
                placeholder="Enter your full name"
                value={formData.name}
                onChange={handleChange}
                aria-invalid={!!fieldErrors.name}
                aria-describedby={fieldErrors.name ? 'name-error' : undefined}
                className={fieldErrors.name ? styles.inputInvalid : undefined}
                disabled={loading}
              />
              {fieldErrors.name && (
                <span id="name-error" className={styles.fieldError}>
                  {fieldErrors.name}
                </span>
              )}
            </div>

            <div className={styles.inputGroup}>
              <label htmlFor="phone-input"><Phone size={16} /> Phone</label>
              <input
                id="phone-input"
                ref={phoneRef}
                name="phone"
                type="tel"
                inputMode="numeric"
                placeholder="+1 (123)-456-7890"
                value={formData.phone}
                onChange={handlePhoneChange}
                aria-invalid={!!fieldErrors.phone}
                aria-describedby={fieldErrors.phone ? 'phone-error' : undefined}
                className={fieldErrors.phone ? styles.inputInvalid : undefined}
                disabled={loading}
              />
              {fieldErrors.phone && (
                <span id="phone-error" className={styles.fieldError}>
                  {fieldErrors.phone}
                </span>
              )}
            </div>
          </div>

          <div className={styles.inputGroup}>
            <label htmlFor="email-input"><Mail size={16} /> Email Address</label>
            <input
              id="email-input"
              ref={emailRef}
              name="email"
              type="email"
              placeholder="Enter email address"
              value={formData.email}
              onChange={handleChange}
              aria-invalid={!!fieldErrors.email}
              aria-describedby={fieldErrors.email ? 'email-error' : undefined}
              className={fieldErrors.email ? styles.inputInvalid : undefined}
              disabled={loading}
            />
            {fieldErrors.email && (
              <span id="email-error" className={styles.fieldError}>
                {fieldErrors.email}
              </span>
            )}
          </div>

          <div className={styles.passwordSection}>
            <div className={styles.inputRow}>
              <div className={styles.inputGroup}>
                <label htmlFor="password-input"><Lock size={16} /> Password</label>
                <input
                  id="password-input"
                  ref={passwordRef}
                  name="password"
                  type="password"
                  placeholder="Enter Password"
                  value={formData.password}
                  onChange={handleChange}
                  maxLength={128}
                  autoComplete="new-password"
                  aria-invalid={!!fieldErrors.password}
                  aria-describedby={fieldErrors.password ? 'password-error' : undefined}
                  className={fieldErrors.password ? styles.inputInvalid : undefined}
                  disabled={loading}
                />
                {fieldErrors.password && (
                  <span id="password-error" className={styles.fieldError}>
                    {fieldErrors.password}
                  </span>
                )}
              </div>

              <div className={styles.inputGroup}>
                <label htmlFor="confirm-password-input"><Lock size={16} /> Confirm</label>
                <input
                  id="confirm-password-input"
                  ref={confirmPasswordRef}
                  name="confirmPassword"
                  type="password"
                  placeholder="Re-enter password"
                  value={formData.confirmPassword}
                  onChange={handleChange}
                  maxLength={128}
                  autoComplete="new-password"
                  aria-invalid={!!fieldErrors.confirmPassword}
                  aria-describedby={fieldErrors.confirmPassword ? 'confirm-error' : undefined}
                  className={fieldErrors.confirmPassword ? styles.inputInvalid : undefined}
                  disabled={loading}
                />
                {fieldErrors.confirmPassword && (
                  <span id="confirm-error" className={styles.fieldError}>
                    {fieldErrors.confirmPassword}
                  </span>
                )}
              </div>
            </div>

            <PasswordStrengthIndicator
              password={formData.password}
              confirmPassword={formData.confirmPassword}
            />
          </div>

          <div className={styles.terms}>
            <label className={styles.checkboxLabel}>
              <input
                ref={termsRef}
                type="checkbox"
                checked={agreedToTerms}
                onChange={(e) => {
                  setAgreedToTerms(e.target.checked);
                  if (e.target.checked) {
                    setFieldErrors(prev => ({ ...prev, terms: '' }));
                  }
                }}
                aria-invalid={!!fieldErrors.terms}
                aria-describedby={fieldErrors.terms ? 'terms-error' : undefined}
                disabled={loading}
              />
              <span>I agree to the <Link href="/terms">Terms of Service</Link> and <Link href="/privacy">Privacy Policy</Link></span>
            </label>
            {fieldErrors.terms && (
              <span id="terms-error" className={styles.fieldError}>
                {fieldErrors.terms}
              </span>
            )}
          </div>

          <button
            type="submit"
            className={styles.signupBtn}
            disabled={loading}
          >
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
