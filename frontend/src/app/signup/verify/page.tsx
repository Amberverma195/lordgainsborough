'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { ShieldCheck, ArrowRight, Mail } from 'lucide-react';
import styles from './verify.module.css';
import { persistAuth } from '@/lib/auth';
import { API_BASE } from '@/lib/orders';

const RESEND_COOLDOWN_SECONDS = 30;
const MAX_RESENDS = 5;

interface PendingSignup {
  name: string;
  email: string;
  phone: string;
  password: string;
}

function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  if (!local || !domain) return email;
  const visible = local.slice(0, 2);
  return `${visible}${'*'.repeat(Math.max(local.length - 2, 1))}@${domain}`;
}

export default function SignupVerifyPage() {
  const router = useRouter();
  const [pendingSignup, setPendingSignup] = useState<PendingSignup | null>(null);
  const [otp, setOtp] = useState(['', '', '', '', '', '']);
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [cooldownSeconds, setCooldownSeconds] = useState(RESEND_COOLDOWN_SECONDS);
  const [resendsRemaining, setResendsRemaining] = useState(MAX_RESENDS);
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      const stored = sessionStorage.getItem('pendingSignup');
      if (!stored) {
        router.replace('/signup');
        return;
      }
      setPendingSignup(JSON.parse(stored));
    }, 0);

    return () => window.clearTimeout(timeout);
  }, [router]);

  useEffect(() => {
    if (cooldownSeconds <= 0) return;
    const timer = window.setTimeout(() => {
      setCooldownSeconds((prev) => Math.max(0, prev - 1));
    }, 1000);
    return () => window.clearTimeout(timer);
  }, [cooldownSeconds]);

  const handleOtpChange = (index: number, value: string) => {
    const digit = value.replace(/\D/g, '').slice(-1);
    const next = [...otp];
    next[index] = digit;
    setOtp(next);
    setError('');

    if (digit && index < 5) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handleOtpKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !otp[index] && index > 0) {
      inputRefs.current[index - 1]?.focus();
    }
  };

  const handleOtpPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!pasted) return;

    const next = [...otp];
    for (let i = 0; i < 6; i++) {
      next[i] = pasted[i] || '';
    }
    setOtp(next);
    setError('');
    inputRefs.current[Math.min(pasted.length, 5)]?.focus();
  };

  const handleResend = async () => {
    if (!pendingSignup || cooldownSeconds > 0 || resendsRemaining <= 0) return;
    setResending(true);
    setError('');
    setSuccess('');

    try {
      const res = await fetch(`${API_BASE}/auth/signup/send-otp`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: pendingSignup.email,
          phone: pendingSignup.phone,
        }),
      });

      if (res.ok) {
        setSuccess('A new verification code has been sent to your email.');
        setOtp(['', '', '', '', '', '']);
        setCooldownSeconds(RESEND_COOLDOWN_SECONDS);
        setResendsRemaining((prev) => Math.max(0, prev - 1));
        inputRefs.current[0]?.focus();
      } else {
        const message = await res.text();
        if (res.status === 429) {
          const retryAfter = Number(res.headers.get('Retry-After'));
          if (Number.isFinite(retryAfter) && retryAfter > 0) {
            setCooldownSeconds(retryAfter);
          }
          if (message.toLowerCase().includes('maximum')) {
            setResendsRemaining(0);
          }
        }
        setError(message || 'Failed to resend code. Please try again.');
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setResending(false);
    }
  };

  const handleCreateAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pendingSignup) return;

    const code = otp.join('');
    if (code.length !== 6) {
      setError('Please enter the 6-digit verification code');
      return;
    }

    setLoading(true);
    setError('');
    setSuccess('');

    try {
      const res = await fetch(`${API_BASE}/auth/signup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: pendingSignup.name,
          email: pendingSignup.email,
          phone: pendingSignup.phone,
          password: pendingSignup.password,
          otp: code,
        }),
      });

      if (res.status === 201) {
        const data = await res.json();
        sessionStorage.removeItem('pendingSignup');
        persistAuth(data);
        window.dispatchEvent(new Event('storage'));
        router.push('/profile');
        return;
      }

      const message = await res.text();
      if (res.status === 409) {
        setError(message || 'An account already exists with this email or phone number.');
      } else if (res.status === 400) {
        setError(message || 'Invalid verification code');
      } else {
        setError(message || 'Something went wrong. Please try again.');
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  if (!pendingSignup) {
    return (
      <div className={styles.container}>
        <div className={styles.spinner} />
      </div>
    );
  }

  const resendDisabled =
    resending || loading || cooldownSeconds > 0 || resendsRemaining <= 0;

  let resendLabel = 'Resend Code';
  if (resending) {
    resendLabel = 'Sending...';
  } else if (resendsRemaining <= 0) {
    resendLabel = 'Resend limit reached';
  } else if (cooldownSeconds > 0) {
    resendLabel = `Resend Code (${cooldownSeconds}s)`;
  }

  return (
    <div className={styles.container}>
      <motion.div
        className={`${styles.verifyCard} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className={styles.header}>
          <div className={styles.iconWrapper}>
            <ShieldCheck size={32} />
          </div>
          <h1>Verify Your Email</h1>
          <p>
            We sent a 6-digit confirmation code to<br />
            <span className={styles.emailHighlight}>
              <Mail size={14} /> {maskEmail(pendingSignup.email)}
            </span>
          </p>
          <p className={styles.hint}>
            It may take up to 2 minutes to arrive. Check spam and promotions folders too.
          </p>
        </div>

        {error && <div className={styles.errorBanner}>{error}</div>}
        {success && <div className={styles.successBanner}>{success}</div>}

        <form className={styles.form} onSubmit={handleCreateAccount}>
          <div className={styles.otpGroup}>
            <label>Confirmation Code</label>
            <div className={styles.otpInputs}>
              {otp.map((digit, index) => (
                <input
                  key={index}
                  ref={(el) => { inputRefs.current[index] = el; }}
                  type="text"
                  inputMode="numeric"
                  maxLength={1}
                  value={digit}
                  onChange={(e) => handleOtpChange(index, e.target.value)}
                  onKeyDown={(e) => handleOtpKeyDown(index, e)}
                  onPaste={index === 0 ? handleOtpPaste : undefined}
                  className={styles.otpInput}
                  aria-label={`Digit ${index + 1}`}
                />
              ))}
            </div>
          </div>

          <button type="submit" className={styles.verifyBtn} disabled={loading}>
            {loading ? 'Creating Account...' : 'Create Account'} <ArrowRight size={18} />
          </button>
        </form>

        <div className={styles.actions}>
          <button
            type="button"
            className={styles.resendBtn}
            onClick={handleResend}
            disabled={resendDisabled}
          >
            {resendLabel}
          </button>
          {resendsRemaining > 0 && resendsRemaining < MAX_RESENDS && (
            <p className={styles.resendHint}>
              {resendsRemaining} resend{resendsRemaining === 1 ? '' : 's'} remaining
            </p>
          )}
        </div>
      </motion.div>
    </div>
  );
}
