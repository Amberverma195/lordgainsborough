'use client';

import React, { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { motion, AnimatePresence } from 'framer-motion';
import {
  LogIn,
  Mail,
  Lock,
  ArrowRight,
  ArrowLeft,
  Eye,
  EyeOff,
  KeyRound,
  ShieldCheck,
  Phone,
  RefreshCw,
} from 'lucide-react';
import styles from './login.module.css';
import { apiFetch } from '@/lib/apiFetch';
import { persistAuth } from '@/lib/auth';
import {
  createServerCooldown,
  LoginApiResponse,
  loginErrorMessage,
  readJsonObject,
  remainingServerCooldownSeconds,
  resolveRetryAfterSeconds,
  ServerCooldownState,
} from '@/lib/authSecurity';
import { isValidEmail } from '@/lib/email';
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
  PasswordResetChallengeState,
  PasswordResetCode,
  passwordResetCooldownSeconds,
  readPasswordResetResponse,
  savePasswordResetChallengeState,
} from '@/lib/passwordReset';
import {
  applyPasswordResetCrossTabPayload,
  PasswordResetCrossTabPayload,
  subscribePasswordResetCrossTab,
  toPasswordResetCrossTabPayload,
} from '@/lib/passwordResetCrossTab';
import {
  extractNationalDigits,
  formatLoginIdentifier,
  looksLikeEmail,
  normalizeIdentifier,
  sanitizeIdentifierInput,
} from '@/lib/phone';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';

type StepName =
  | 'login-identifier'
  | 'login-password'
  | 'reset-request'
  | 'reset-verify'
  | 'reset-complete';

function subscribeToLocation(callback: () => void) {
  window.addEventListener('popstate', callback);
  return () => window.removeEventListener('popstate', callback);
}

export default function LoginPage() {
  const router = useRouter();

  // Navigation & step machine
  const [step, setStep] = useState<StepName>(() => {
    if (typeof window !== 'undefined') {
      const params = new URLSearchParams(window.location.search);
      if (params.get('intent') === 'recover') {
        return 'reset-request';
      }
    }
    return 'login-identifier';
  });
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  // Reset password states
  const [resetOtp, setResetOtp] = useState('');
  const [resetPassword, setResetPassword] = useState('');
  const [resetConfirmPassword, setResetConfirmPassword] = useState('');
  const [showResetPassword, setShowResetPassword] = useState(false);
  const [showResetConfirmPassword, setShowResetConfirmPassword] = useState(false);
  const [challenge, setChallenge] = useState<PasswordResetChallengeState | null>(null);

  // Common UI states
  const [loading, setLoading] = useState(false);
  const [activeRequest, setActiveRequest] = useState<'login' | 'send' | 'resend' | 'reset' | null>(null);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loginCooldown, setLoginCooldown] = useState<ServerCooldownState | null>(null);
  const [rateCooldown, setRateCooldown] = useState<ServerCooldownState | null>(null);
  const [nowTick, setNowTick] = useState(() => Date.now());

  const requestInFlight = useRef(false);
  const challengeRef = useRef<PasswordResetChallengeState | null>(null);
  const crossTabRef = useRef<ReturnType<typeof subscribePasswordResetCrossTab> | null>(null);
  const historyPushedRef = useRef(false);
  const stepRef = useRef<StepName>(step);
  const identifierRef = useRef(identifier);

  useEffect(() => {
    stepRef.current = step;
    identifierRef.current = identifier;
  }, [step, identifier]);

  const accountDeleted = useSyncExternalStore(
    subscribeToLocation,
    () => typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('accountDeleted') === '1',
    () => false
  );

  const loginCooldownSeconds = remainingServerCooldownSeconds(loginCooldown, nowTick);
  const resetCooldownSec = passwordResetCooldownSeconds(challenge, nowTick);
  const rateCooldownSec = remainingServerCooldownSeconds(rateCooldown, nowTick);
  const challengeExpired = isPasswordResetChallengeExpired(challenge, nowTick);
  const resendExhausted = isPasswordResetResendExhausted(challenge);

  // Challenge persistence & state syncing
  const updateChallenge = useCallback((next: PasswordResetChallengeState | null) => {
    challengeRef.current = next;
    setChallenge(next);
    if (next) {
      savePasswordResetChallengeState(next);
    } else {
      clearPasswordResetChallengeState();
    }
  }, []);

  const publishCrossTab = useCallback((
    state: PasswordResetChallengeState | null,
    type: PasswordResetCrossTabPayload['type']
  ) => {
    const handle = crossTabRef.current;
    if (!handle) return;
    handle.publish(toPasswordResetCrossTabPayload(state, type, handle.tabId));
  }, []);

  const clearSensitiveFields = useCallback(() => {
    setPassword('');
    setResetOtp('');
    setResetPassword('');
    setResetConfirmPassword('');
  }, []);

  // Browser navigation popstate listener
  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      // If user presses browser Back while in any reset step, return to clean login
      if (!event.state?.lgbAuthRecovery) {
        historyPushedRef.current = false;
        setStep((currentStep) => {
          if (currentStep === 'reset-request' || currentStep === 'reset-verify' || currentStep === 'reset-complete') {
            updateChallenge(null);
            clearSensitiveFields();
            setError('');
            setSuccess('');
            return 'login-identifier';
          }
          if (currentStep === 'login-password') {
            clearSensitiveFields();
            setError('');
            return 'login-identifier';
          }
          return currentStep;
        });
      } else {
        // Forward navigation into recovery state
        historyPushedRef.current = true;
        clearSensitiveFields();
        setStep((currentStep) => {
          if (currentStep === 'login-identifier' || currentStep === 'login-password') {
            setError('');
            setSuccess('');
            return challengeRef.current ? 'reset-verify' : 'reset-request';
          }
          return currentStep;
        });
      }
    };

    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [clearSensitiveFields, updateChallenge]);

  // Initial load check for ?intent=recover
  useEffect(() => {
    if (typeof window !== 'undefined') {
      const params = new URLSearchParams(window.location.search);
      if (params.get('intent') === 'recover') {
        historyPushedRef.current = false;
        try {
          window.history.replaceState({ lgbAuthRecovery: true }, '', '/login');
        } catch {}
      }
    }
  }, []);

  // Cross-tab synchronization
  useEffect(() => {
    const handle = subscribePasswordResetCrossTab((payload) => {
      const currentStep = stepRef.current;
      const activeChallenge = challengeRef.current;
      const activeIdentifier = identifierRef.current?.trim()
        ? normalizeIdentifier(identifierRef.current.trim())
        : '';

      // Cross-tab events only apply if this tab is actively in the recovery flow
      const isInRecovery = currentStep === 'reset-request' || currentStep === 'reset-verify';
      if (!isInRecovery) {
        return;
      }

      // Check whether payload matches this tab's active recovery identity
      const payloadEmail = payload.email ? normalizeIdentifier(payload.email) : '';
      const payloadChallengeId = payload.challengeId;

      const matchesChallengeId = Boolean(
        payloadChallengeId && activeChallenge?.challengeId && payloadChallengeId === activeChallenge.challengeId
      );
      const matchesActiveEmail = Boolean(
        payloadEmail && (
          (activeChallenge?.email && normalizeIdentifier(activeChallenge.email) === payloadEmail)
          || (activeIdentifier && activeIdentifier === payloadEmail)
        )
      );
      const matchesIdentity = matchesChallengeId || matchesActiveEmail;

      if (!matchesIdentity) {
        // Discard: belongs to a different recovery session or unrelated account
        return;
      }

      if (payload.type === 'completed') {
        updateChallenge(null);
        clearSensitiveFields();
        setError('');
        setSuccess('Your password has been updated in another window. You can now sign in.');
        setStep('login-identifier');
        return;
      }
      if (payload.type === 'cleared') {
        updateChallenge(null);
        clearSensitiveFields();
        setError('');
        setSuccess('');
        setStep('login-identifier');
        return;
      }
      const remote = applyPasswordResetCrossTabPayload(payload);
      if (!remote) return;
      updateChallenge(remote);
      setIdentifier(remote.email);
      setStep('reset-verify');
      setError('');
      setSuccess('Your password reset request was updated in another tab.');
    });
    crossTabRef.current = handle;

    return () => {
      handle.close();
      crossTabRef.current = null;
    };
  }, [clearSensitiveFields, updateChallenge]);

  // Periodic tick for authoritative countdowns
  useEffect(() => {
    const timer = window.setInterval(() => {
      const now = Date.now();
      setNowTick(now);
      if (loginCooldown && remainingServerCooldownSeconds(loginCooldown, now) === 0) {
        setLoginCooldown(null);
      }
      if (rateCooldown && remainingServerCooldownSeconds(rateCooldown, now) === 0) {
        setRateCooldown(null);
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [loginCooldown, rateCooldown]);

  // Validation helper
  const validateIdentifier = (val: string): { valid: boolean; error?: string } => {
    const trimmed = val.trim();
    if (!trimmed) {
      return { valid: false, error: 'Please enter your email or phone number.' };
    }
    if (looksLikeEmail(trimmed)) {
      if (!isValidEmail(trimmed)) {
        return { valid: false, error: 'Please enter a valid email address.' };
      }
      return { valid: true };
    }
    const digits = extractNationalDigits(trimmed);
    if (digits.length !== 10) {
      return { valid: false, error: 'Please enter a valid 10-digit phone number.' };
    }
    return { valid: true };
  };

  // --- Step 1: Identifier Continue ---
  const handleIdentifierSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    const validation = validateIdentifier(identifier);
    if (!validation.valid) {
      setError(validation.error || 'Invalid identifier.');
      return;
    }
    setStep('login-password');
  };

  // --- Step 2: Login Password Submit ---
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestInFlight.current || loginCooldownSeconds > 0) return;
    requestInFlight.current = true;
    setError('');
    setLoading(true);
    setActiveRequest('login');

    const normalized = normalizeIdentifier(identifier);

    try {
      const res = await apiFetch(`${API_BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          emailOrPhone: normalized,
          email: normalized,
          password,
        }),
      });

      const body = await readJsonObject<LoginApiResponse>(res) as LoginApiResponse;
      if (res.ok) {
        persistAuth(body);
        window.dispatchEvent(new Event('storage'));
        router.push('/profile');
        return;
      }

      if (res.status === 429 || body.code === 'LOGIN_RATE_LIMITED') {
        const retrySeconds = resolveRetryAfterSeconds(
          res.headers.get('Retry-After'),
          body.retryAfterSeconds,
          body.serverTime
        );
        const nextCooldown = createServerCooldown(retrySeconds, body.serverTime);
        setLoginCooldown(nextCooldown);
        setError(loginErrorMessage(res.status, body.code));
        return;
      }
      setError(loginErrorMessage(res.status, body.code));
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setLoading(false);
      setActiveRequest(null);
    }
  };

  // Switch to Recovery Flow
  const goToResetRequest = () => {
    setError('');
    setSuccess('');
    clearSensitiveFields();
    if (!historyPushedRef.current) {
      try {
        window.history.pushState({ lgbAuthRecovery: true }, '', '/login');
        historyPushedRef.current = true;
      } catch {}
    }
    setStep('reset-request');
  };

  // --- Step 3: Send Reset OTP ---
  const handleSendResetOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestInFlight.current || rateCooldownSec > 0) return;
    setError('');
    setSuccess('');

    const validation = validateIdentifier(identifier);
    if (!validation.valid) {
      setError(validation.error || 'Please enter a valid email or phone number.');
      return;
    }

    requestInFlight.current = true;
    setActiveRequest('send');
    setLoading(true);

    const normalized = normalizeIdentifier(identifier);

    try {
      const idempotencyKey = crypto.randomUUID();
      const res = await fetchPasswordResetWithRetry((key) =>
        apiFetch(`${API_BASE}/auth/forgot-password/send-otp`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify({ email: normalized }),
        }), idempotencyKey);

      const body = await readPasswordResetResponse(res);
      const retrySeconds = resolveRetryAfterSeconds(
        res.headers.get('Retry-After'),
        body.retryAfterSeconds,
        body.serverTime
      );

      if (body.code === 'RESET_RATE_LIMITED' || res.status === 429) {
        setRateCooldown(createServerCooldown(retrySeconds, body.serverTime));
        setError(retrySeconds > 0 ? `Please wait ${retrySeconds}s before trying again.` : 'Too many reset attempts.');
        return;
      }

      const nextChallenge = applyPasswordResetResponse(
        challengeRef.current,
        normalized,
        body,
        retrySeconds
      );

      if (res.status === 202 && nextChallenge) {
        updateChallenge(nextChallenge);
        publishCrossTab(nextChallenge, 'challenge-updated');
        clearSensitiveFields();
        setStep('reset-verify');
        setSuccess('If an account matches that email or phone, a reset code has been sent.');
        return;
      }

      setCodedResetError(body.code, retrySeconds);
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setActiveRequest(null);
      setLoading(false);
    }
  };

  // --- Step 4: Resend Reset OTP ---
  const handleResendResetOtp = async () => {
    const current = challengeRef.current;
    if (
      requestInFlight.current
      || !current
      || resetCooldownSec > 0
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
        apiFetch(`${API_BASE}/auth/forgot-password/resend-otp`, {
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

      if (body.code === 'RESET_RATE_LIMITED' || res.status === 429) {
        setRateCooldown(createServerCooldown(retrySeconds, body.serverTime));
      }

      const nextChallenge = applyPasswordResetResponse(current, current.email, body, retrySeconds);
      if (nextChallenge) {
        updateChallenge(nextChallenge);
        publishCrossTab(nextChallenge, 'challenge-updated');
      }

      if (res.ok && body.code === 'RESET_CODE_SENT') {
        setResetOtp('');
        setSuccess('If the reset request is still valid, a new code has been sent.');
        return;
      }
      setCodedResetError(body.code, retrySeconds);
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setActiveRequest(null);
    }
  };

  // --- Step 4: Change Identifier from Verification Screen ---
  const handleChangeIdentifier = () => {
    const prevChallenge = challengeRef.current;
    updateChallenge(null);
    clearSensitiveFields();
    setError('');
    setSuccess('');
    publishCrossTab(prevChallenge, 'cleared');
    setStep('reset-request');
  };

  // --- Step 4: Complete Reset Password ---
  const handleResetPasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const current = challengeRef.current;
    if (requestInFlight.current || !current) return;
    setError('');
    setSuccess('');

    if (resetOtp.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }

    const passwordError = getPasswordValidationMessage(resetPassword, resetConfirmPassword);
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
    setLoading(true);

    try {
      const idempotencyKey = crypto.randomUUID();
      const res = await fetchPasswordResetWithRetry((key) =>
        apiFetch(`${API_BASE}/auth/forgot-password/reset`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify({
            email: current.email,
            challengeId: current.challengeId,
            otp: resetOtp.replace(/\D/g, '').slice(0, 6),
            password: sanitizePasswordInput(resetPassword),
          }),
        }), idempotencyKey);

      const body = await readPasswordResetResponse(res);
      const retrySeconds = resolveRetryAfterSeconds(
        res.headers.get('Retry-After'),
        body.retryAfterSeconds,
        body.serverTime
      );

      if (body.code === 'RESET_RATE_LIMITED' || res.status === 429) {
        setRateCooldown(createServerCooldown(retrySeconds, body.serverTime));
      }

      if (res.ok && body.code === 'PASSWORD_RESET_COMPLETED') {
        publishCrossTab(current, 'completed');
        updateChallenge(null);
        clearSensitiveFields();
        setStep('reset-complete');
        setSuccess('Your password has been updated. You can now sign in.');
        return;
      }

      applyPasswordResetResponse(current, current.email, body, retrySeconds);
      setCodedResetError(body.code, retrySeconds);
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      requestInFlight.current = false;
      setActiveRequest(null);
      setLoading(false);
    }
  };

  // Exit Recovery back to Login Identifier
  const returnToLogin = () => {
    const prevChallenge = challengeRef.current;
    updateChallenge(null);
    clearSensitiveFields();
    setError('');
    setSuccess('');
    publishCrossTab(prevChallenge, 'cleared');
    setStep('login-identifier');

    if (historyPushedRef.current) {
      historyPushedRef.current = false;
      try {
        window.history.back();
      } catch {
        try {
          window.history.replaceState(null, '', '/login');
        } catch {}
      }
    } else {
      try {
        window.history.replaceState(null, '', '/login');
      } catch {}
    }
  };

  const setCodedResetError = (code: PasswordResetCode | undefined, retrySeconds: number) => {
    if (code === 'RESET_CHALLENGE_EXPIRED') {
      setError('This password reset session has expired. Please start again.');
    } else if (code === 'RESET_CODE_RESEND_LIMIT_REACHED') {
      setError('No more codes can be sent for this reset request. Please start again.');
    } else if (code === 'RESET_CODE_INVALID') {
      setError('The verification code is invalid or has expired.');
    } else if (code === 'RESET_VERIFICATION_LOCKED') {
      setError('Too many incorrect attempts. Please start a new password reset request.');
    } else if (code === 'RESET_CODE_RESEND_COOLDOWN' || code === 'RESET_RATE_LIMITED' || retrySeconds > 0) {
      setError(retrySeconds > 0 ? `Please wait ${retrySeconds}s before trying again.` : 'Please wait before trying again.');
    } else {
      setError('Unable to complete that request. Please try again.');
    }
  };

  const resetPasswordValidation = validatePassword(resetPassword);
  const resetConfirmMismatch =
    resetConfirmPassword.length > 0 && !passwordsMatch(resetPassword, resetConfirmPassword);
  const canSubmitReset =
    resetOtp.length === 6 &&
    resetPasswordValidation.valid &&
    passwordsMatch(resetPassword, resetConfirmPassword) &&
    resetConfirmPassword.length > 0;

  const resendDisabled =
    loading
    || rateCooldownSec > 0
    || resetCooldownSec > 0
    || challengeExpired
    || resendExhausted;

  let resendLabel = 'Resend Code';
  if (activeRequest === 'resend') {
    resendLabel = 'Sending...';
  } else if (rateCooldownSec > 0) {
    resendLabel = `Try Again (${rateCooldownSec}s)`;
  } else if (challengeExpired) {
    resendLabel = 'Session expired';
  } else if (resendExhausted) {
    resendLabel = 'Resend limit reached';
  } else if (resetCooldownSec > 0) {
    resendLabel = `Resend Code (${resetCooldownSec}s)`;
  }

  return (
    <div className={styles.container}>
      <motion.div
        className={`${styles.loginCard} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        {/* Step Header */}
        <div className={styles.header}>
          <div className={styles.iconWrapper}>
            {step === 'reset-complete' ? (
              <ShieldCheck size={32} />
            ) : step === 'reset-request' || step === 'reset-verify' ? (
              <KeyRound size={32} />
            ) : (
              <LogIn size={32} />
            )}
          </div>
          <h1>
            {step === 'login-identifier' || step === 'login-password'
              ? 'Welcome Back'
              : step === 'reset-complete'
              ? 'Password Updated'
              : 'Reset Password'}
          </h1>
          <p>
            {step === 'login-identifier' && 'Sign in to your Lord Gainsborough account'}
            {step === 'login-password' && 'Enter your password to continue'}
            {step === 'reset-request' && 'Enter your registered email or phone to receive a reset code'}
            {step === 'reset-verify' && 'Enter the verification code and choose a new password'}
            {step === 'reset-complete' && 'Your account password has been changed successfully'}
          </p>
        </div>

        {/* Notifications */}
        {accountDeleted && step === 'login-identifier' && (
          <p role="status" className={styles.successBanner}>
            Your account, orders, cart, and reservations have been deleted.
          </p>
        )}
        {error && <div className={styles.errorBanner}>{error}</div>}
        {success && <div className={styles.successBanner}>{success}</div>}

        <AnimatePresence mode="wait">
          {/* STEP 1: LOGIN IDENTIFIER */}
          {step === 'login-identifier' && (
            <motion.form
              key="step-identifier"
              className={styles.form}
              onSubmit={handleIdentifierSubmit}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              <div className={styles.inputGroup}>
                <label>
                  {looksLikeEmail(identifier) ? <Mail size={16} /> : <Phone size={16} />} Email or Phone Number
                </label>
                <input
                  type="text"
                  inputMode="email"
                  autoComplete="username"
                  placeholder="Email or phone number"
                  value={identifier}
                  onChange={(e) => setIdentifier(formatLoginIdentifier(sanitizeIdentifierInput(e.target.value)))}
                  disabled={loading}
                  required
                  autoFocus
                />
              </div>

              <button type="submit" className={styles.loginBtn} disabled={loading}>
                Continue <ArrowRight size={18} />
              </button>

              <p className={styles.footer}>
                Don&apos;t have an account? <Link href="/signup">Sign up now</Link>
              </p>
            </motion.form>
          )}

          {/* STEP 2: LOGIN PASSWORD */}
          {step === 'login-password' && (
            <motion.form
              key="step-password"
              className={styles.form}
              onSubmit={handleLogin}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              <div className={styles.identifierBadge}>
                <span className={styles.identifierText}>
                  {looksLikeEmail(identifier) ? <Mail size={14} /> : <Phone size={14} />} {identifier}
                </span>
                <button
                  type="button"
                  className={styles.changeIdentifierBtn}
                  onClick={() => {
                    setError('');
                    setStep('login-identifier');
                  }}
                  disabled={loading}
                >
                  Change
                </button>
              </div>

              <div className={styles.inputGroup}>
                <label><Lock size={16} /> Password</label>
                <div className={styles.passwordField}>
                  <input
                    type={showPassword ? 'text' : 'password'}
                    placeholder="••••••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={loading}
                    autoComplete="current-password"
                    required
                    autoFocus
                  />
                  <button
                    type="button"
                    className={styles.passwordToggle}
                    onClick={() => setShowPassword((prev) => !prev)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    disabled={loading}
                  >
                    {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </div>

              <div className={styles.options}>
                <button
                  type="button"
                  className={styles.forgotPass}
                  onClick={goToResetRequest}
                  disabled={loading}
                >
                  Forgot password?
                </button>
              </div>

              <button
                type="submit"
                className={styles.loginBtn}
                disabled={loading || loginCooldownSeconds > 0}
              >
                {loading
                  ? 'Authenticating...'
                  : loginCooldownSeconds > 0
                  ? `Try Again (${loginCooldownSeconds}s)`
                  : 'Sign In'} <ArrowRight size={18} />
              </button>

              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.backLink}
                  onClick={() => {
                    setError('');
                    setStep('login-identifier');
                  }}
                  disabled={loading}
                >
                  <ArrowLeft size={16} /> Use a different email/phone
                </button>
              </div>
            </motion.form>
          )}

          {/* STEP 3: RESET REQUEST */}
          {step === 'reset-request' && (
            <motion.form
              key="step-reset-req"
              className={styles.form}
              onSubmit={handleSendResetOtp}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              <div className={styles.inputGroup}>
                <label>
                  {looksLikeEmail(identifier) ? <Mail size={16} /> : <Phone size={16} />} Email or Phone Number
                </label>
                <input
                  type="text"
                  placeholder="Enter email address or phone number"
                  value={identifier}
                  onChange={(e) => setIdentifier(formatLoginIdentifier(sanitizeIdentifierInput(e.target.value)))}
                  disabled={loading}
                  required
                  autoFocus
                />
              </div>

              <button
                type="submit"
                className={styles.primaryBtn}
                disabled={loading || rateCooldownSec > 0}
              >
                {activeRequest === 'send'
                  ? 'Sending Code...'
                  : rateCooldownSec > 0
                  ? `Try Again (${rateCooldownSec}s)`
                  : 'Send Reset Code'} <ArrowRight size={18} />
              </button>

              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.backLink}
                  onClick={returnToLogin}
                  disabled={loading}
                >
                  <ArrowLeft size={16} /> Back to Sign In
                </button>
              </div>
            </motion.form>
          )}

          {/* STEP 4: RESET VERIFICATION */}
          {step === 'reset-verify' && (
            <motion.form
              key="step-reset-ver"
              className={styles.form}
              onSubmit={handleResetPasswordSubmit}
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              <div className={styles.identifierBadge}>
                <span className={styles.identifierText}>
                  {looksLikeEmail(identifier) ? <Mail size={14} /> : <Phone size={14} />} {identifier}
                </span>
                <button
                  type="button"
                  className={styles.changeIdentifierBtn}
                  onClick={handleChangeIdentifier}
                  disabled={loading}
                >
                  Change
                </button>
              </div>

              <div className={styles.inputGroup}>
                <label><ShieldCheck size={16} /> Verification Code</label>
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="6-digit code"
                  value={resetOtp}
                  onChange={(e) => setResetOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  pattern="[0-9]{6}"
                  disabled={loading}
                  autoComplete="one-time-code"
                  required
                  autoFocus
                />
              </div>

              <div className={styles.inputGroup}>
                <label><Lock size={16} /> New Password</label>
                <div className={styles.passwordField}>
                  <input
                    type={showResetPassword ? 'text' : 'password'}
                    placeholder="Enter new password"
                    value={resetPassword}
                    onChange={(e) => setResetPassword(sanitizePasswordInput(e.target.value))}
                    maxLength={128}
                    autoComplete="new-password"
                    aria-invalid={resetPassword.length > 0 && !resetPasswordValidation.valid}
                    className={resetPassword.length > 0 && !resetPasswordValidation.valid ? styles.inputInvalid : undefined}
                    disabled={loading}
                    required
                  />
                  <button
                    type="button"
                    className={styles.passwordToggle}
                    onClick={() => setShowResetPassword((prev) => !prev)}
                    aria-label={showResetPassword ? 'Hide password' : 'Show password'}
                    disabled={loading}
                  >
                    {showResetPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </div>

              <div className={styles.inputGroup}>
                <label><Lock size={16} /> Confirm Password</label>
                <div className={styles.passwordField}>
                  <input
                    type={showResetConfirmPassword ? 'text' : 'password'}
                    placeholder="Re-enter new password"
                    value={resetConfirmPassword}
                    onChange={(e) => setResetConfirmPassword(sanitizePasswordInput(e.target.value))}
                    maxLength={128}
                    autoComplete="new-password"
                    aria-invalid={resetConfirmMismatch}
                    className={resetConfirmMismatch ? styles.inputInvalid : undefined}
                    disabled={loading}
                    required
                  />
                  <button
                    type="button"
                    className={styles.passwordToggle}
                    onClick={() => setShowResetConfirmPassword((prev) => !prev)}
                    aria-label={showResetConfirmPassword ? 'Hide password' : 'Show password'}
                    disabled={loading}
                  >
                    {showResetConfirmPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
              </div>

              <PasswordStrengthIndicator password={resetPassword} confirmPassword={resetConfirmPassword} />

              <button
                type="submit"
                className={styles.primaryBtn}
                disabled={loading || !canSubmitReset || challengeExpired || rateCooldownSec > 0}
              >
                {activeRequest === 'reset'
                  ? 'Resetting...'
                  : rateCooldownSec > 0
                  ? `Try Again (${rateCooldownSec}s)`
                  : 'Reset Password'} <ArrowRight size={18} />
              </button>

              <div className={styles.actions}>
                <button
                  type="button"
                  className={styles.textBtn}
                  onClick={handleResendResetOtp}
                  disabled={resendDisabled}
                >
                  {resendLabel}
                </button>
                {challenge?.resendsRemaining !== undefined && (
                  <p className={styles.resendHint}>
                    {challenge.resendsRemaining} resend{challenge.resendsRemaining === 1 ? '' : 's'} remaining
                  </p>
                )}
                <button
                  type="button"
                  className={styles.textBtn}
                  onClick={handleChangeIdentifier}
                  disabled={loading}
                >
                  <RefreshCw size={14} style={{ display: 'inline', marginRight: 4, verticalAlign: 'middle' }} />
                  Change Email or Phone Number
                </button>
                <button
                  type="button"
                  className={styles.backLink}
                  onClick={returnToLogin}
                  disabled={loading}
                >
                  <ArrowLeft size={16} /> Back to Sign In
                </button>
              </div>
            </motion.form>
          )}

          {/* STEP 5: RESET COMPLETE */}
          {step === 'reset-complete' && (
            <motion.div
              key="step-complete"
              className={styles.actions}
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.3 }}
            >
              <button
                type="button"
                className={styles.primaryBtn}
                onClick={returnToLogin}
              >
                Sign In <ArrowRight size={18} />
              </button>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
  );
}
