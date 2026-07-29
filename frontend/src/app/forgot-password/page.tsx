'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { motion } from 'framer-motion';
import { ArrowLeft, ArrowRight, KeyRound, Lock, Mail, ShieldCheck } from 'lucide-react';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';
import {
  createServerCooldown,
  remainingServerCooldownSeconds,
  resolveRetryAfterSeconds,
  ServerCooldownState,
} from '@/lib/authSecurity';
import { API_BASE } from '@/lib/orders';
import {
  getPasswordValidationMessage,
  passwordsMatch,
  sanitizePasswordInput,
  validatePassword,
} from '@/lib/password';
import {
  applyPasswordResetResponse,
  clearPasswordResetChallengeState,
  fetchPasswordResetWithRetry,
  isPasswordResetChallengeExpired,
  isPasswordResetResendExhausted,
  loadPasswordResetChallengeState,
  PasswordResetChallengeState,
  PasswordResetCode,
  passwordResetCooldownSeconds,
  PasswordResetResponse,
  readPasswordResetResponse,
  savePasswordResetChallengeState,
} from '@/lib/passwordReset';
import {
  applyPasswordResetCrossTabPayload,
  PasswordResetCrossTabPayload,
  subscribePasswordResetCrossTab,
  toPasswordResetCrossTabPayload,
} from '@/lib/passwordResetCrossTab';
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
  const [challenge, setChallenge] = useState<PasswordResetChallengeState | null>(null);
  const [rateCooldown, setRateCooldown] = useState<ServerCooldownState | null>(null);
  const [activeRequest, setActiveRequest] = useState<'send' | 'resend' | 'reset' | null>(null);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [nowTick, setNowTick] = useState(() => Date.now());
  const requestInFlight = useRef(false);
  const challengeRef = useRef<PasswordResetChallengeState | null>(null);
  const crossTabRef = useRef<ReturnType<typeof subscribePasswordResetCrossTab> | null>(null);

  const normalizedEmail = email.trim().toLowerCase();
  const loading = activeRequest !== null;
  const cooldownSeconds = passwordResetCooldownSeconds(challenge, nowTick);
  const rateCooldownSeconds = remainingServerCooldownSeconds(rateCooldown, nowTick);
  const challengeExpired = isPasswordResetChallengeExpired(challenge, nowTick);
  const resendExhausted = isPasswordResetResendExhausted(challenge);

  const updateChallenge = useCallback((next: PasswordResetChallengeState | null) => {
    challengeRef.current = next;
    setChallenge(next);
    if (next) {
      savePasswordResetChallengeState(next);
    } else {
      clearPasswordResetChallengeState();
    }
  }, []);

  const publish = useCallback((
    state: PasswordResetChallengeState | null,
    type: PasswordResetCrossTabPayload['type']
  ) => {
    const handle = crossTabRef.current;
    if (!handle) return;
    handle.publish(toPasswordResetCrossTabPayload(state, type, handle.tabId));
  }, []);

  const applyBackendResponse = useCallback((
    body: PasswordResetResponse,
    emailValue: string,
    retryAfterSeconds: number
  ) => {
    const next = applyPasswordResetResponse(
      challengeRef.current,
      emailValue,
      body,
      retryAfterSeconds
    );
    if (next) {
      updateChallenge(next);
      publish(next, 'challenge-updated');
    }
    return next;
  }, [publish, updateChallenge]);

  const clearSensitiveInputs = useCallback(() => {
    setOtp('');
    setPassword('');
    setConfirmPassword('');
  }, []);

  const startOver = useCallback((broadcast = true) => {
    const previous = challengeRef.current;
    updateChallenge(null);
    clearSensitiveInputs();
    setStep('email');
    setError('');
    setSuccess('');
    if (broadcast) {
      publish(previous, 'cleared');
    }
  }, [clearSensitiveInputs, publish, updateChallenge]);

  useEffect(() => {
    const handle = subscribePasswordResetCrossTab((payload) => {
      if (payload.type === 'completed') {
        updateChallenge(null);
        clearSensitiveInputs();
        setError('');
        setSuccess('Your password has been updated. You can now sign in.');
        setStep('complete');
        return;
      }
      if (payload.type === 'cleared') {
        startOver(false);
        return;
      }
      const remote = applyPasswordResetCrossTabPayload(payload);
      if (!remote) return;
      updateChallenge(remote);
      setEmail(remote.email);
      setStep('reset');
      setError('');
      setSuccess('Your password reset request was updated in another tab.');
    });
    crossTabRef.current = handle;

    const restoreTimer = window.setTimeout(() => {
      const stored = loadPasswordResetChallengeState();
      if (stored) {
        updateChallenge(stored);
        setEmail(stored.email);
        setStep('reset');
      }
    }, 0);

    return () => {
      window.clearTimeout(restoreTimer);
      handle.close();
      crossTabRef.current = null;
    };
  }, [clearSensitiveInputs, startOver, updateChallenge]);

  useEffect(() => {
    if (!challenge && !rateCooldown) return;
    const timer = window.setInterval(() => {
      const now = Date.now();
      setNowTick(now);
      if (rateCooldown && remainingServerCooldownSeconds(rateCooldown, now) === 0) {
        setRateCooldown(null);
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [challenge, rateCooldown]);

  const applyRateLimit = (
    body: PasswordResetResponse,
    retrySeconds: number
  ) => {
    if (body.code === 'RESET_RATE_LIMITED') {
      setRateCooldown(createServerCooldown(retrySeconds, body.serverTime));
      setNowTick(Date.now());
    }
  };

  const setCodedError = (
    code: PasswordResetCode | undefined,
    retrySeconds: number
  ) => {
    if (code === 'RESET_CHALLENGE_EXPIRED') {
      setError('This password reset session has expired. Please start again.');
    } else if (code === 'RESET_CODE_RESEND_LIMIT_REACHED') {
      setError('No more codes can be sent for this reset request. Please start again.');
    } else if (code === 'RESET_CODE_INVALID') {
      setError('The verification code is invalid or has expired.');
    } else if (code === 'RESET_VERIFICATION_LOCKED') {
      setError('Too many incorrect attempts. Please start a new password reset request.');
    } else if (
      code === 'RESET_CODE_RESEND_COOLDOWN'
      || code === 'RESET_RATE_LIMITED'
      || retrySeconds > 0
    ) {
      setError(
        retrySeconds > 0
          ? `Please wait ${retrySeconds}s before trying again.`
          : 'Please wait before trying again.'
      );
    } else {
      setError('Unable to complete that request. Please try again.');
    }
  };

  const handleSendOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestInFlight.current) return;
    if (!normalizedEmail) {
      setError('Email is required.');
      return;
    }

    requestInFlight.current = true;
    setActiveRequest('send');
    setError('');
    setSuccess('');

    try {
      const idempotencyKey = crypto.randomUUID();
      const res = await fetchPasswordResetWithRetry((key) =>
        fetch(`${API_BASE}/auth/forgot-password/send-otp`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify({ email: normalizedEmail }),
        }), idempotencyKey);
      const body = await readPasswordResetResponse(res);
      const retrySeconds = resolveRetryAfterSeconds(
        res.headers.get('Retry-After'),
        body.retryAfterSeconds,
        body.serverTime
      );
      applyRateLimit(body, retrySeconds);
      const next = applyBackendResponse(body, normalizedEmail, retrySeconds);

      if (res.status === 202 && next) {
        setStep('reset');
        clearSensitiveInputs();
        setSuccess('If an account matches that email, a reset code has been sent.');
        return;
      }

      setCodedError(body.code, retrySeconds);
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setActiveRequest(null);
    }
  };

  const handleResend = async () => {
    const current = challengeRef.current;
    if (
      requestInFlight.current
      || !current
      || cooldownSeconds > 0
      || challengeExpired
      || resendExhausted
    ) {
      return;
    }

    requestInFlight.current = true;
    setActiveRequest('resend');
    setError('');
    setSuccess('');

    try {
      const idempotencyKey = crypto.randomUUID();
      const res = await fetchPasswordResetWithRetry((key) =>
        fetch(`${API_BASE}/auth/forgot-password/resend-otp`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify({
            email: current.email,
            challengeId: current.challengeId,
          }),
        }), idempotencyKey);
      const body = await readPasswordResetResponse(res);
      const retrySeconds = resolveRetryAfterSeconds(
        res.headers.get('Retry-After'),
        body.retryAfterSeconds,
        body.serverTime
      );
      applyRateLimit(body, retrySeconds);
      applyBackendResponse(body, current.email, retrySeconds);

      if (res.ok && body.code === 'RESET_CODE_SENT') {
        setOtp('');
        setSuccess('If the reset request is still valid, a new code has been sent.');
        return;
      }
      setCodedError(body.code, retrySeconds);
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setActiveRequest(null);
    }
  };

  const handleResetPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    const current = challengeRef.current;
    if (requestInFlight.current || !current) return;
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

    if (challengeExpired) {
      setError('This password reset session has expired. Please start again.');
      return;
    }

    requestInFlight.current = true;
    setActiveRequest('reset');
    try {
      const idempotencyKey = crypto.randomUUID();
      const res = await fetchPasswordResetWithRetry((key) =>
        fetch(`${API_BASE}/auth/forgot-password/reset`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify({
            email: current.email,
            challengeId: current.challengeId,
            otp: otp.replace(/\D/g, '').slice(0, 6),
            password: sanitizePasswordInput(password),
          }),
        }), idempotencyKey);
      const body = await readPasswordResetResponse(res);
      const retrySeconds = resolveRetryAfterSeconds(
        res.headers.get('Retry-After'),
        body.retryAfterSeconds,
        body.serverTime
      );
      applyRateLimit(body, retrySeconds);

      if (res.ok && body.code === 'PASSWORD_RESET_COMPLETED') {
        publish(current, 'completed');
        updateChallenge(null);
        setStep('complete');
        clearSensitiveInputs();
        setSuccess('Your password has been updated. You can now sign in.');
        return;
      }

      applyBackendResponse(body, current.email, retrySeconds);
      setCodedError(body.code, retrySeconds);
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setActiveRequest(null);
    }
  };

  const passwordValidation = validatePassword(password);
  const confirmMismatch = confirmPassword.length > 0 && !passwordsMatch(password, confirmPassword);
  const canReset =
    otp.length === 6 &&
    passwordValidation.valid &&
    passwordsMatch(password, confirmPassword) &&
    confirmPassword.length > 0;
  const resendDisabled =
    loading
    || rateCooldownSeconds > 0
    || cooldownSeconds > 0
    || challengeExpired
    || resendExhausted;

  let resendLabel = 'Resend Code';
  if (activeRequest === 'resend') {
    resendLabel = 'Sending...';
  } else if (rateCooldownSeconds > 0) {
    resendLabel = `Try Again (${rateCooldownSeconds}s)`;
  } else if (challengeExpired) {
    resendLabel = 'Session expired';
  } else if (resendExhausted) {
    resendLabel = 'Resend limit reached';
  } else if (cooldownSeconds > 0) {
    resendLabel = `Resend Code (${cooldownSeconds}s)`;
  }

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

            <button
              type="submit"
              className={styles.primaryBtn}
              disabled={loading || rateCooldownSeconds > 0}
            >
              {activeRequest === 'send'
                ? 'Sending Code...'
                : rateCooldownSeconds > 0
                  ? `Try Again (${rateCooldownSeconds}s)`
                  : 'Send Reset Code'} <ArrowRight size={18} />
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
                autoComplete="one-time-code"
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

            <button
              type="submit"
              className={styles.primaryBtn}
              disabled={loading || !canReset || challengeExpired || rateCooldownSeconds > 0}
            >
              {activeRequest === 'reset'
                ? 'Resetting...'
                : rateCooldownSeconds > 0
                  ? `Try Again (${rateCooldownSeconds}s)`
                  : 'Reset Password'} <ArrowRight size={18} />
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
            <>
              <button
                type="button"
                className={styles.textBtn}
                onClick={handleResend}
                disabled={resendDisabled}
              >
                {resendLabel}
              </button>
              {challenge?.resendsRemaining !== undefined && (
                <p className={styles.resendHint}>
                  {challenge.resendsRemaining} resend{challenge.resendsRemaining === 1 ? '' : 's'} remaining
                </p>
              )}
              {(challengeExpired || resendExhausted) && (
                <button
                  type="button"
                  className={styles.textBtn}
                  onClick={() => startOver()}
                  disabled={loading}
                >
                  Start a New Reset Request
                </button>
              )}
            </>
          )}
          <Link href="/login" className={styles.backLink}>
            <ArrowLeft size={16} /> Back to Sign In
          </Link>
        </div>
      </motion.div>
    </div>
  );
}
