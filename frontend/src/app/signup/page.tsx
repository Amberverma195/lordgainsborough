'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { motion, AnimatePresence } from 'framer-motion';
import {
  UserPlus,
  Mail,
  Lock,
  User,
  ArrowRight,
  Phone,
  Eye,
  EyeOff,
  ShieldCheck,
  CheckCircle2,
  X,
  RefreshCw,
} from 'lucide-react';
import styles from './signup.module.css';
import { apiFetch } from '@/lib/apiFetch';
import { persistAuth } from '@/lib/auth';
import { isValidEmail, suggestEmailDomainTypo } from '@/lib/email';
import { API_BASE } from '@/lib/orders';
import { passwordsMatch, validatePassword } from '@/lib/password';
import { formatPhoneInput, isValidPhoneNumber } from '@/lib/phone';
import {
  applyBackendChallengeState,
  clearAllSignupSession,
  cooldownSecondsFromServerClocks,
  fetchSignupOtpStatus,
  fetchWithIdempotentRetry,
  isChallengeExpiredState,
  isResendExhausted,
  loadPendingSignupCredentials,
  loadSignupOtpChallengeState,
  PendingSignupCredentials,
  readSignupOtpResponse,
  savePendingSignupCredentials,
  saveSignupOtpChallengeState,
  SignupOtpChallengeState,
} from '@/lib/signupOtp';
import {
  applyCrossTabPayload,
  subscribeSignupOtpCrossTab,
  toCrossTabPayload,
} from '@/lib/signupOtpCrossTab';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';
import { CURRENT_TERMS_VERSION, CURRENT_PRIVACY_VERSION } from '@/lib/legalConsent';

type SignupStep = 'signup-form' | 'signup-verify';

interface ToastState {
  id: string;
  message: string;
}

export default function SignupPage() {
  const router = useRouter();

  // Initial stored signup retrieval
  const [initialStored] = useState(() => {
    if (typeof window === 'undefined') return { creds: null, chal: null };
    const creds = loadPendingSignupCredentials();
    const chal = loadSignupOtpChallengeState();
    if (creds && chal && chal.code !== 'OTP_VERIFIED') {
      return { creds, chal };
    }
    return { creds: null, chal: null };
  });

  // Step state
  const [step, setStep] = useState<SignupStep>(() =>
    initialStored.creds ? 'signup-verify' : 'signup-form'
  );

  // Form inputs
  const [formData, setFormData] = useState(() => ({
    name: initialStored.creds?.name || '',
    email: initialStored.creds?.email || '',
    phone: initialStored.creds?.phone || '',
    password: '',
    confirmPassword: '',
  }));
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [emailTypoSuggestion, setEmailTypoSuggestion] = useState<string | null>(null);
  const [emailConfirmedAsEntered, setEmailConfirmedAsEntered] = useState<string | null>(null);
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [conflictPending, setConflictPending] = useState(false);

  // OTP Verification state
  const [pendingSignup, setPendingSignup] = useState<PendingSignupCredentials | null>(
    () => initialStored.creds
  );
  const [challenge, setChallenge] = useState<SignupOtpChallengeState | null>(
    () => initialStored.chal
  );
  const [otp, setOtp] = useState(['', '', '', '', '', '']);
  const [hasResent, setHasResent] = useState(false);

  // Common UI states
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);
  const [error, setError] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  const [toast, setToast] = useState<ToastState | null>(null);
  const [nowTick, setNowTick] = useState(() => Date.now());

  // Refs
  const nameRef = useRef<HTMLInputElement>(null);
  const phoneRef = useRef<HTMLInputElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const confirmPasswordRef = useRef<HTMLInputElement>(null);
  const termsRef = useRef<HTMLInputElement>(null);
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);

  const crossTabRef = useRef<ReturnType<typeof subscribeSignupOtpCrossTab> | null>(null);
  const challengeRef = useRef<SignupOtpChallengeState | null>(null);
  const toastTimerRef = useRef<NodeJS.Timeout | null>(null);
  const syncSeqRef = useRef(0);
  const requestInFlight = useRef(false);

  const showToast = useCallback((message: string) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setToast({ id: String(Date.now()), message });
    toastTimerRef.current = setTimeout(() => {
      setToast(null);
      toastTimerRef.current = null;
    }, 4000);
  }, []);

  const dismissToast = useCallback(() => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
      toastTimerRef.current = null;
    }
    setToast(null);
  }, []);

  const publishChallenge = useCallback((
    state: SignupOtpChallengeState,
    type: 'challenge-updated' | 'verified' | 'cleared' = 'challenge-updated'
  ) => {
    const handle = crossTabRef.current;
    if (!handle) return;
    handle.publish(toCrossTabPayload(state, type, handle.tabId));
  }, []);

  const applyChallengeFromBackend = useCallback((
    body: Parameters<typeof applyBackendChallengeState>[1],
    email: string,
    broadcast = true
  ) => {
    const previous = loadSignupOtpChallengeState() || challengeRef.current;
    const next = applyBackendChallengeState(previous, body, email);
    challengeRef.current = next;
    setChallenge(next);
    saveSignupOtpChallengeState(next);
    if (broadcast) {
      publishChallenge(next, 'challenge-updated');
    }
    return next;
  }, [publishChallenge]);

  // Sync challenge status on mount/focus
  const syncChallengeStatus = useCallback(async (credentials: PendingSignupCredentials) => {
    const currentSeq = ++syncSeqRef.current;
    const cached = loadSignupOtpChallengeState() || challengeRef.current;
    try {
      const { body } = await fetchSignupOtpStatus(
        credentials.email,
        cached?.challengeId,
        cached?.attemptToken
      );
      if (currentSeq !== syncSeqRef.current) return;

      const current = loadSignupOtpChallengeState() || challengeRef.current;
      const next = applyBackendChallengeState(current, body, credentials.email);
      challengeRef.current = next;
      setChallenge(next);
      saveSignupOtpChallengeState(next);
      publishChallenge(next);

      if (body.code === 'OTP_DELIVERY_UNKNOWN') {
        setInfoMessage(body.message || "We couldn't confirm whether the code was sent. Please check your inbox and spam folder.");
      } else if (body.code === 'OTP_CHALLENGE_EXPIRED') {
        setError(body.message || 'This verification session has expired. Please start signup again.');
      } else if (body.code === 'OTP_RESEND_LIMIT_REACHED') {
        setError("You've reached the resend limit. Please restart verification later.");
      }
    } catch {
      // Offline or temporary error
    }
  }, [publishChallenge]);

  // Cross-tab synchronization
  useEffect(() => {
    const handle = subscribeSignupOtpCrossTab((payload) => {
      if (payload.type === 'verified') {
        setError('');
        clearAllSignupSession();
        router.replace('/profile');
        return;
      }
      if (payload.type === 'cleared') {
        challengeRef.current = null;
        setChallenge(null);
        clearAllSignupSession();
        setStep('signup-form');
        return;
      }
      const next = applyCrossTabPayload(loadSignupOtpChallengeState() || challengeRef.current, payload);
      if (!next) return;
      challengeRef.current = next;
      setChallenge(next);
      saveSignupOtpChallengeState(next);
      if (next.code === 'OTP_CHALLENGE_EXPIRED') {
        setError(next.message || 'This verification session has expired. Please start signup again.');
      } else if (next.code === 'OTP_RESEND_LIMIT_REACHED') {
        setError("You've reached the resend limit. Please restart verification later.");
      }
    });
    crossTabRef.current = handle;
    return () => {
      handle.close();
      crossTabRef.current = null;
    };
  }, [router]);

  // Check for existing pending signup session on mount
  useEffect(() => {
    if (initialStored.creds) {
      challengeRef.current = initialStored.chal;
      void syncChallengeStatus(initialStored.creds);
    }
  }, [initialStored, syncChallengeStatus]);

  // Window focus listener for status sync
  useEffect(() => {
    const onFocus = () => {
      if (pendingSignup && step === 'signup-verify') {
        void syncChallengeStatus(pendingSignup);
      }
    };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [pendingSignup, step, syncChallengeStatus]);

  // Countdown timer tick
  useEffect(() => {
    const timer = window.setInterval(() => setNowTick(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const focusAndScrollTo = (ref: React.RefObject<HTMLInputElement | null>) => {
    if (ref.current) {
      ref.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
      ref.current.focus({ preventScroll: true });
    }
  };

  // --- Step 1: Handle Signup Form Submit ---
  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestInFlight.current) return;
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
    } else {
      const cleanEmail = formData.email.trim().toLowerCase();
      const typo = suggestEmailDomainTypo(formData.email);
      if (typo && typo.toLowerCase() !== cleanEmail && emailConfirmedAsEntered !== cleanEmail) {
        setEmailTypoSuggestion(typo);
        newErrors.email = `Did you mean ${typo}? Use suggestion or confirm your email below.`;
        if (!firstInvalidRef) firstInvalidRef = emailRef;
      }
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
      newErrors.terms = 'Please agree to the Terms of Service and acknowledge the Privacy Policy to continue.';
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
    requestInFlight.current = true;

    try {
      const cleanEmail = formData.email.trim().toLowerCase();
      const existingChallenge = loadSignupOtpChallengeState();
      const existingToken =
        existingChallenge && existingChallenge.email.toLowerCase() === cleanEmail
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
        termsAccepted: true,
        termsVersion: CURRENT_TERMS_VERSION,
        privacyVersion: CURRENT_PRIVACY_VERSION,
      };

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      };
      if (existingToken) {
        headers['X-Signup-Attempt-Token'] = existingToken;
      }

      const res = await fetchWithIdempotentRetry(
        (key) =>
          apiFetch(`${API_BASE}/auth/signup/send-otp`, {
            method: 'POST',
            headers: {
              ...headers,
              'Idempotency-Key': key,
            },
            body: JSON.stringify(payload),
          }),
        idempotencyKey
      );

      if (res.ok || res.status === 202) {
        const body = await readSignupOtpResponse(res);
        clearAllSignupSession();
        const initialChallengeBody = {
          ...body,
          nextResendAt:
            body.nextResendAt && Date.parse(body.nextResendAt) <= Date.parse(body.serverTime || '')
              ? body.nextResendAt
              : body.serverTime,
        };
        const challengeState = applyBackendChallengeState(existingChallenge, initialChallengeBody, cleanEmail);
        saveSignupOtpChallengeState(challengeState);
        const credentials: PendingSignupCredentials = {
          name: formData.name.trim(),
          email: cleanEmail,
          phone: formData.phone,
        };
        savePendingSignupCredentials(credentials);

        setPendingSignup(credentials);
        setChallenge(challengeState);
        challengeRef.current = challengeState;
        publishChallenge(challengeState, 'challenge-updated');

        setOtp(['', '', '', '', '', '']);
        setStep('signup-verify');
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
      requestInFlight.current = false;
      setLoading(false);
    }
  };

  // --- Step 2: Handle OTP Input Navigation ---
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

  // --- Step 2: Resend OTP Code ---
  const handleResend = async () => {
    if (!pendingSignup || resending || loading) return;
    if (activeCooldownSeconds > 0 || isResendExhausted(challenge) || isChallengeExpiredState(challenge, nowTick)) {
      return;
    }

    syncSeqRef.current++;
    setHasResent(true);
    setResending(true);
    setError('');
    setInfoMessage('');

    const idempotencyKey = crypto.randomUUID();
    const currentChallenge = challenge || challengeRef.current || loadSignupOtpChallengeState();
    const payload = {
      email: pendingSignup.email,
      phone: pendingSignup.phone,
      attemptToken: currentChallenge?.attemptToken,
    };

    try {
      const res = await fetchWithIdempotentRetry(
        (key) =>
          apiFetch(`${API_BASE}/auth/signup/send-otp`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'Idempotency-Key': key,
              ...(currentChallenge?.attemptToken ? { 'X-Signup-Attempt-Token': currentChallenge.attemptToken } : {}),
            },
            body: JSON.stringify(payload),
          }),
        idempotencyKey
      );

      const body = await readSignupOtpResponse(res);
      syncSeqRef.current++;
      applyChallengeFromBackend(body, pendingSignup.email);

      if (res.status === 202 || res.status === 200) {
        if (body.code === 'OTP_DELIVERY_UNKNOWN') {
          setInfoMessage(body.message || "We couldn't confirm whether the code was sent. Please check your inbox.");
        } else if (body.code === 'OTP_ALREADY_ACTIVE') {
          setInfoMessage(body.message || 'A code was already sent recently. Please check your inbox.');
        } else {
          showToast('A new verification code has been sent to your email.');
        }
        setOtp(['', '', '', '', '', '']);
        inputRefs.current[0]?.focus();
      } else if (res.status === 409 && body.code === 'OTP_ATTEMPT_CONFLICT') {
        setError('A signup verification conflict was detected. Please restart verification.');
      } else if (res.status === 429) {
        setError(body.message || 'Please wait before requesting another code.');
      } else if (res.status === 410) {
        setError(body.message || 'This verification session has expired. Please restart signup.');
      } else {
        setError(body.message || "We couldn't send your code. Please try again.");
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setResending(false);
    }
  };

  // --- Step 2: Change Email Address / Start New Verification (Restart signup flow cleanly) ---
  const handleChangeEmail = () => {
    const prev = challengeRef.current || challenge;
    clearAllSignupSession();
    challengeRef.current = null;
    setChallenge(null);
    setPendingSignup(null);
    setOtp(['', '', '', '', '', '']);
    setError('');
    setInfoMessage('');
    if (prev) {
      publishChallenge(prev, 'cleared');
    }
    setStep('signup-form');
  };

  // --- Step 2: Verify OTP & Create Account ---
  const handleVerifyAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pendingSignup || loading || resending) return;

    const code = otp.join('');
    if (code.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }

    if (isChallengeExpiredState(challenge, nowTick)) {
      setError('This verification session has expired. Please start signup again.');
      return;
    }

    setLoading(true);
    setError('');
    setInfoMessage('');

    try {
      const res = await apiFetch(`${API_BASE}/auth/signup`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(challenge?.attemptToken ? { 'X-Signup-Attempt-Token': challenge.attemptToken } : {}),
        },
        body: JSON.stringify({
          email: pendingSignup.email,
          otp: code,
          challengeId: challenge?.challengeId,
          attemptToken: challenge?.attemptToken,
          termsAccepted: true,
          termsVersion: CURRENT_TERMS_VERSION,
          privacyVersion: CURRENT_PRIVACY_VERSION,
        }),
      });

      if (res.status === 201) {
        const data = await res.json();
        persistAuth(data);
        window.dispatchEvent(new Event('storage'));
        clearAllSignupSession();
        publishChallenge(
          {
            email: pendingSignup.email,
            challengeId: challenge?.challengeId,
            attemptToken: challenge?.attemptToken,
            code: 'OTP_VERIFIED',
          },
          'verified'
        );
        router.push('/profile');
        return;
      }

      const body = await readSignupOtpResponse(res);
      applyChallengeFromBackend(body, pendingSignup.email);
      if (res.status === 409 && body.code === 'OTP_ATTEMPT_CONFLICT') {
        setError('This verification session is not valid for this browser. Please return to signup and restart.');
      } else {
        setError(body.message || 'Unable to complete verification. Please check the code and try again.');
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setFieldErrors(prev => ({ ...prev, [name]: '' }));
    if (name === 'email') {
      const typo = suggestEmailDomainTypo(value);
      if (typo && typo.toLowerCase() !== value.trim().toLowerCase()) {
        setEmailTypoSuggestion(typo);
      } else {
        setEmailTypoSuggestion(null);
      }
      if (emailConfirmedAsEntered && emailConfirmedAsEntered !== value.trim().toLowerCase()) {
        setEmailConfirmedAsEntered(null);
      }
    }
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

  // Cooldown calculation
  const exhausted = isResendExhausted(challenge);
  const challengeExpired = isChallengeExpiredState(challenge, nowTick);
  const canVerify = otp.every(Boolean);

  const cooldownSeconds = challenge
    ? cooldownSecondsFromServerClocks(
        challenge.serverTime,
        challenge.nextResendAt,
        nowTick,
        challenge.receivedAtMs
      )
    : 0;

  const resendConsumed = challenge?.resendsRemaining !== undefined && challenge.resendsRemaining < 5;
  const activeCooldownSeconds = hasResent || resendConsumed ? cooldownSeconds : 0;

  const resendDisabled =
    resending || loading || activeCooldownSeconds > 0 || exhausted || challengeExpired;

  let resendLabel = 'Resend Code';
  if (resending) {
    resendLabel = 'Sending...';
  } else if (challengeExpired) {
    resendLabel = 'Session expired';
  } else if (exhausted) {
    resendLabel = 'Resend limit reached';
  } else if (activeCooldownSeconds > 0) {
    resendLabel = `Resend available in ${activeCooldownSeconds}s`;
  }

  return (
    <div className={styles.container}>
      {toast && (
        <div role="status" aria-live="polite" className={styles.toast}>
          <div className={styles.toastIconWrapper}>
            <CheckCircle2 size={18} />
          </div>
          <span className={styles.toastMessage}>{toast.message}</span>
          <button
            type="button"
            className={styles.toastCloseBtn}
            onClick={dismissToast}
            aria-label="Dismiss notification"
          >
            <X size={16} />
          </button>
        </div>
      )}

      <motion.div
        className={`${step === 'signup-verify' ? styles.verifyCard : styles.signupCard} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <div className={styles.header}>
          <div className={styles.iconWrapper}>
            {step === 'signup-verify' ? <ShieldCheck size={32} /> : <UserPlus size={32} />}
          </div>
          <h1>{step === 'signup-verify' ? 'Verify Your Email' : 'Join the Club'}</h1>
          <p>
            {step === 'signup-verify' ? (
              <>
                We sent a 6-digit confirmation code to<br />
                <span className={styles.emailHighlight}>
                  <Mail size={14} /> {pendingSignup?.email || formData.email}
                </span>
              </>
            ) : (
              'Create your account for exclusive dining experiences'
            )}
          </p>
          {step === 'signup-verify' && (
            <p className={styles.hint}>
              Your code was sent. It may take up to 2 minutes to arrive. Check spam and promotions folders too.
            </p>
          )}
        </div>

        {error && <div className={styles.errorBanner}>{error}</div>}
        {infoMessage && <div className={styles.infoBanner}>{infoMessage}</div>}

        <AnimatePresence mode="wait">
          {/* STEP 1: SIGNUP FORM */}
          {step === 'signup-form' && (
            <motion.div
              key="signup-form-step"
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              {conflictPending && (
                <div
                  style={{
                    background: 'rgba(217, 119, 6, 0.1)',
                    border: '1px solid rgba(217, 119, 6, 0.3)',
                    borderRadius: '8px',
                    padding: '1rem',
                    marginBottom: '1.25rem',
                    color: '#fbbf24',
                    fontSize: '0.9rem',
                    textAlign: 'center',
                  }}
                >
                  <p style={{ margin: '0 0 0.75rem 0' }}>
                    A signup verification session is already in progress for this email address.
                  </p>
                  <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'center' }}>
                    <button
                      type="button"
                      onClick={() => setStep('signup-verify')}
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
                  {emailTypoSuggestion && emailConfirmedAsEntered !== formData.email.trim().toLowerCase() && (
                    <div className={styles.emailSuggestion} role="alert">
                      <div className={styles.suggestionPrompt}>
                        <span>Did you mean</span>
                        <button
                          type="button"
                          className={styles.suggestionLink}
                          onClick={() => {
                            const corrected = emailTypoSuggestion;
                            setFormData(prev => ({ ...prev, email: corrected }));
                            setEmailTypoSuggestion(null);
                            setEmailConfirmedAsEntered(corrected.toLowerCase());
                            setFieldErrors(prev => ({ ...prev, email: '' }));
                          }}
                        >
                          {emailTypoSuggestion}
                        </button>
                        <span>?</span>
                      </div>
                      <div className={styles.suggestionActions}>
                        <button
                          type="button"
                          className={styles.confirmEmailAsEnteredBtn}
                          onClick={() => {
                            setEmailConfirmedAsEntered(formData.email.trim().toLowerCase());
                            setEmailTypoSuggestion(null);
                            setFieldErrors(prev => ({ ...prev, email: '' }));
                          }}
                        >
                          Use my email as entered
                        </button>
                      </div>
                    </div>
                  )}
                </div>

                <div className={styles.passwordSection}>
                  <div className={styles.inputRow}>
                    <div className={styles.inputGroup}>
                      <label htmlFor="password-input"><Lock size={16} /> Password</label>
                      <div className={styles.passwordWrapper}>
                        <input
                          id="password-input"
                          ref={passwordRef}
                          name="password"
                          type={showPassword ? 'text' : 'password'}
                          placeholder="Enter Password"
                          value={formData.password}
                          onChange={handleChange}
                          maxLength={128}
                          autoComplete="new-password"
                          aria-invalid={!!fieldErrors.password}
                          aria-describedby={fieldErrors.password ? 'password-error' : undefined}
                          className={`${styles.passwordInput} ${fieldErrors.password ? styles.inputInvalid : ''}`}
                          disabled={loading}
                        />
                        <button
                          type="button"
                          className={styles.passwordToggleBtn}
                          onClick={() => setShowPassword(prev => !prev)}
                          aria-label={showPassword ? 'Hide password' : 'Show password'}
                          aria-pressed={showPassword}
                          disabled={loading}
                        >
                          {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                        </button>
                      </div>
                      {fieldErrors.password && (
                        <span id="password-error" className={styles.fieldError}>
                          {fieldErrors.password}
                        </span>
                      )}
                    </div>

                    <div className={styles.inputGroup}>
                      <label htmlFor="confirm-password-input"><Lock size={16} /> Confirm</label>
                      <div className={styles.passwordWrapper}>
                        <input
                          id="confirm-password-input"
                          ref={confirmPasswordRef}
                          name="confirmPassword"
                          type={showConfirmPassword ? 'text' : 'password'}
                          placeholder="Re-enter password"
                          value={formData.confirmPassword}
                          onChange={handleChange}
                          maxLength={128}
                          autoComplete="new-password"
                          aria-invalid={!!fieldErrors.confirmPassword}
                          aria-describedby={fieldErrors.confirmPassword ? 'confirm-error' : undefined}
                          className={`${styles.passwordInput} ${fieldErrors.confirmPassword ? styles.inputInvalid : ''}`}
                          disabled={loading}
                        />
                        <button
                          type="button"
                          className={styles.passwordToggleBtn}
                          onClick={() => setShowConfirmPassword(prev => !prev)}
                          aria-label={showConfirmPassword ? 'Hide password' : 'Show password'}
                          aria-pressed={showConfirmPassword}
                          disabled={loading}
                        >
                          {showConfirmPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                        </button>
                      </div>
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
                    <span>
                      I agree to the{' '}
                      <Link href="/terms" target="_blank" rel="noopener noreferrer">
                        Terms of Service
                      </Link>{' '}
                      and acknowledge that I have read the{' '}
                      <Link href="/privacy" target="_blank" rel="noopener noreferrer">
                        Privacy Policy
                      </Link>
                      .
                    </span>
                  </label>
                  {fieldErrors.terms && (
                    <span id="terms-error" className={styles.fieldError}>
                      {fieldErrors.terms}
                    </span>
                  )}
                </div>

                <button type="submit" className={styles.signupBtn} disabled={loading}>
                  {loading ? 'Sending Code...' : 'Create Account'} <ArrowRight size={18} />
                </button>
              </form>

              <p className={styles.footer}>
                Already have an account? <Link href="/login">Sign in here</Link>
              </p>
            </motion.div>
          )}

          {/* STEP 2: OTP VERIFICATION */}
          {step === 'signup-verify' && (
            <motion.div
              key="signup-verify-step"
              initial={{ opacity: 0, x: -10 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 10 }}
              transition={{ duration: 0.2 }}
            >
              <form className={styles.form} onSubmit={handleVerifyAccount}>
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
                        disabled={loading || resending || challengeExpired}
                        autoFocus={index === 0}
                      />
                    ))}
                  </div>
                </div>

                {challengeExpired ? (
                  <button
                    type="button"
                    className={styles.verifyBtn}
                    onClick={handleChangeEmail}
                  >
                    Start New Verification <ArrowRight size={18} />
                  </button>
                ) : (
                  <button
                    type="submit"
                    className={styles.verifyBtn}
                    disabled={loading || resending || !canVerify}
                  >
                    {loading ? 'Verifying...' : 'Verify Email & Create Account'} <ArrowRight size={18} />
                  </button>
                )}
              </form>

              <div className={styles.actions} style={{ marginTop: '1.25rem' }}>
                <button
                  type="button"
                  className={styles.resendBtn}
                  onClick={handleResend}
                  disabled={resendDisabled}
                >
                  {resendLabel}
                </button>

                <button
                  type="button"
                  className={styles.backLink}
                  onClick={handleChangeEmail}
                  disabled={loading}
                >
                  <RefreshCw size={14} style={{ display: 'inline', marginRight: 4, verticalAlign: 'middle' }} />
                  Change Email Address
                </button>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
  );
}
