'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { ShieldCheck, ArrowRight, Lock, Mail } from 'lucide-react';
import PasswordStrengthIndicator from '@/components/PasswordStrengthIndicator';
import styles from './verify.module.css';
import { persistAuth } from '@/lib/auth';
import { API_BASE } from '@/lib/orders';
import {
  getPasswordValidationMessage,
  passwordsMatch,
  sanitizePasswordInput,
  validatePassword,
} from '@/lib/password';
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
  saveSignupOtpChallengeState,
  SignupOtpChallengeState,
} from '@/lib/signupOtp';
import {
  applyCrossTabPayload,
  subscribeSignupOtpCrossTab,
  toCrossTabPayload,
} from '@/lib/signupOtpCrossTab';

function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  if (!local || !domain) return email;
  const visible = local.slice(0, 2);
  return `${visible}${'*'.repeat(Math.max(local.length - 2, 1))}@${domain}`;
}

export default function SignupVerifyPage() {
  const router = useRouter();
  const [pendingSignup, setPendingSignup] = useState<PendingSignupCredentials | null>(null);
  const [challenge, setChallenge] = useState<SignupOtpChallengeState | null>(null);
  const [otp, setOtp] = useState(['', '', '', '', '', '']);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);
  const [syncing, setSyncing] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [cooldownSeconds, setCooldownSeconds] = useState(0);
  const [nowTick, setNowTick] = useState(() => Date.now());
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const crossTabRef = useRef<ReturnType<typeof subscribeSignupOtpCrossTab> | null>(null);

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
    const previous = loadSignupOtpChallengeState();
    const next = applyBackendChallengeState(previous, body, email);
    setChallenge(next);
    saveSignupOtpChallengeState(next);
    setCooldownSeconds(cooldownSecondsFromServerClocks(next.serverTime, next.nextResendAt));
    if (broadcast) {
      publishChallenge(next, body.code === 'OTP_VERIFIED' ? 'verified' : 'challenge-updated');
    }
    return next;
  }, [publishChallenge]);

  const syncChallengeStatus = useCallback(async (credentials: PendingSignupCredentials) => {
    const cached = loadSignupOtpChallengeState();
    setSyncing(true);
    try {
      const { body } = await fetchSignupOtpStatus(
        credentials.email,
        cached?.challengeId
      );
      const next = applyBackendChallengeState(cached, body, credentials.email);
      setChallenge(next);
      saveSignupOtpChallengeState(next);
      setCooldownSeconds(cooldownSecondsFromServerClocks(next.serverTime, next.nextResendAt));
      publishChallenge(next);

      if (body.code === 'OTP_DELIVERY_UNKNOWN') {
        setSuccess(body.message || 'Delivery status is unknown. Check your inbox before requesting another code.');
      } else if (body.code === 'OTP_CHALLENGE_EXPIRED') {
        setError(body.message || 'This verification session has expired. Please start signup again.');
        setSuccess('');
      } else if (body.code === 'OTP_RESEND_LIMIT_REACHED') {
        setError(body.message || 'No more verification codes can be sent for this signup attempt.');
        setSuccess('');
      }
    } catch {
      // Keep last known non-secret timing until network recovers; do not invent a new timer.
      if (cached) {
        setChallenge(cached);
        setCooldownSeconds(cooldownSecondsFromServerClocks(cached.serverTime, cached.nextResendAt));
      }
    } finally {
      setSyncing(false);
    }
  }, [publishChallenge]);

  useEffect(() => {
    const handle = subscribeSignupOtpCrossTab((payload) => {
      if (payload.type === 'verified') {
        setError('');
        setSuccess('This email was verified in another tab. Continuing…');
        clearAllSignupSession();
        router.replace('/login');
        return;
      }
      if (payload.type === 'cleared') {
        setChallenge(null);
        clearAllSignupSession();
        router.replace('/signup');
        return;
      }
      const next = applyCrossTabPayload(loadSignupOtpChallengeState(), payload);
      if (!next) return;
      setChallenge(next);
      saveSignupOtpChallengeState(next);
      setCooldownSeconds(cooldownSecondsFromServerClocks(next.serverTime, next.nextResendAt));
      if (next.code === 'OTP_CHALLENGE_EXPIRED') {
        setError(next.message || 'This verification session has expired. Please start signup again.');
      } else if (next.code === 'OTP_RESEND_LIMIT_REACHED') {
        setError(next.message || 'No more verification codes can be sent for this signup attempt.');
      } else if (next.code === 'OTP_DELIVERY_UNKNOWN') {
        setSuccess(next.message || 'Delivery status is unknown. Check your inbox before requesting another code.');
      }
    });
    crossTabRef.current = handle;
    return () => {
      handle.close();
      crossTabRef.current = null;
    };
  }, [router]);
  useEffect(() => {
    const timeout = window.setTimeout(() => {
      const stored = loadPendingSignupCredentials();
      if (!stored) {
        router.replace('/signup');
        return;
      }
      setPendingSignup(stored);
      const cached = loadSignupOtpChallengeState();
      if (cached) {
        setChallenge(cached);
        setCooldownSeconds(cooldownSecondsFromServerClocks(cached.serverTime, cached.nextResendAt));
      }
      void syncChallengeStatus(stored);
    }, 0);
    return () => window.clearTimeout(timeout);
  }, [router, syncChallengeStatus]);

  useEffect(() => {
    const onFocus = () => {
      if (pendingSignup) {
        void syncChallengeStatus(pendingSignup);
      }
    };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [pendingSignup, syncChallengeStatus]);

  useEffect(() => {
    const timer = window.setInterval(() => setNowTick(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!challenge) return;
    // Preserve the existing signup timer behavior; this effect mirrors server challenge state.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCooldownSeconds(cooldownSecondsFromServerClocks(
      challenge.serverTime,
      challenge.nextResendAt,
      nowTick
    ));
  }, [challenge, nowTick]);

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
    if (!pendingSignup || resending || loading) return;
    if (cooldownSeconds > 0 || isResendExhausted(challenge) || isChallengeExpiredState(challenge)) {
      return;
    }

    setResending(true);
    setError('');
    setSuccess('');

    const idempotencyKey = crypto.randomUUID();
    const payload = {
      email: pendingSignup.email,
      phone: pendingSignup.phone,
    };

    try {
      const res = await fetchWithIdempotentRetry((key) =>
        fetch(`${API_BASE}/auth/signup/send-otp`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
          },
          body: JSON.stringify(payload),
        }), idempotencyKey);

      const body = await readSignupOtpResponse(res);
      applyChallengeFromBackend(body, pendingSignup.email);

      if (res.status === 202 || res.status === 200) {
        setSuccess(
          body.code === 'OTP_DELIVERY_UNKNOWN'
            ? (body.message || 'Delivery status is unknown. Check your inbox before requesting another code.')
            : (body.message || 'A new verification code has been sent to your email.')
        );
        setOtp(['', '', '', '', '', '']);
        inputRefs.current[0]?.focus();
      } else if (res.status === 429) {
        setError(body.message || 'Please wait before requesting another code.');
        if (body.code === 'OTP_RESEND_LIMIT_REACHED') {
          setSuccess('');
        }
      } else if (res.status === 410) {
        setError(body.message || 'This verification session has expired.');
      } else {
        setError(body.message || 'Failed to resend code. Please try again.');
      }

      // Always re-sync so backend overrides any stale local countdown.
      await syncChallengeStatus(pendingSignup);
    } catch {
      setError('Unable to connect to the server. Please try again.');
      await syncChallengeStatus(pendingSignup);
    } finally {
      setResending(false);
    }
  };

  const handleCreateAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!pendingSignup || loading || resending) return;

    const code = otp.join('');
    if (code.length !== 6) {
      setError('Please enter the 6-digit verification code');
      return;
    }

    const passwordError = getPasswordValidationMessage(password, confirmPassword);
    if (passwordError) {
      setError(passwordError);
      return;
    }
    if (!confirmPassword || !passwordsMatch(password, confirmPassword)) {
      setError('Please re-enter your password and make sure both passwords match.');
      return;
    }

    if (isChallengeExpiredState(challenge)) {
      setError('This verification session has expired. Please start signup again.');
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
          password: sanitizePasswordInput(password),
          otp: code,
        }),
      });

      if (res.status === 201) {
        const data = await res.json();
        publishChallenge({
          email: pendingSignup.email,
          challengeId: challenge?.challengeId,
          code: 'OTP_VERIFIED',
        }, 'verified');
        clearAllSignupSession();
        setPassword('');
        setConfirmPassword('');
        persistAuth(data);
        window.dispatchEvent(new Event('storage'));
        router.push('/profile');
        return;
      }

      const body = await readSignupOtpResponse(res);
      applyChallengeFromBackend(body, pendingSignup.email);
      setError(body.message || 'Something went wrong. Please try again.');
      if (body.code === 'OTP_CHALLENGE_EXPIRED' || body.code === 'OTP_EXPIRED') {
        await syncChallengeStatus(pendingSignup);
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  if (!pendingSignup || (syncing && !challenge)) {
    return (
      <div className={styles.container}>
        <div className={styles.spinner} />
      </div>
    );
  }

  const exhausted = isResendExhausted(challenge);
  const challengeExpired = isChallengeExpiredState(challenge, nowTick);
  const resendsRemaining = challenge?.resendsRemaining ?? 0;
  const deliveryUnknown = challenge?.code === 'OTP_DELIVERY_UNKNOWN';
  const passwordValidation = validatePassword(password);
  const confirmMismatch =
    confirmPassword.length > 0 && !passwordsMatch(password, confirmPassword);
  const canCreateAccount =
    otp.every(Boolean)
    && passwordValidation.valid
    && confirmPassword.length > 0
    && passwordsMatch(password, confirmPassword);

  const resendDisabled =
    resending || loading || syncing || cooldownSeconds > 0 || exhausted || challengeExpired;

  let resendLabel = 'Resend Code';
  if (resending) {
    resendLabel = 'Sending...';
  } else if (challengeExpired) {
    resendLabel = 'Session expired';
  } else if (exhausted) {
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
        {deliveryUnknown && !success && (
          <div className={styles.successBanner}>
            Delivery status is unknown. Check your inbox before requesting another code.
          </div>
        )}
        {challengeExpired && (
          <div className={styles.errorBanner}>
            This verification session has expired. Please start signup again.
          </div>
        )}

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
                  disabled={loading || resending || challengeExpired}
                />
              ))}
            </div>
          </div>

          <div className={styles.passwordSection}>
            <p className={styles.passwordPrompt}>
              For your security, re-enter the password you want to use. It is not stored during verification.
            </p>
            <div className={styles.inputGroup}>
              <label><Lock size={16} /> Password</label>
              <input
                type="password"
                placeholder="Enter your password"
                value={password}
                onChange={(e) => setPassword(sanitizePasswordInput(e.target.value))}
                maxLength={128}
                autoComplete="new-password"
                aria-invalid={password.length > 0 && !passwordValidation.valid}
                className={
                  password.length > 0 && !passwordValidation.valid
                    ? styles.inputInvalid
                    : undefined
                }
                disabled={loading || resending || challengeExpired}
                required
              />
            </div>
            <div className={styles.inputGroup}>
              <label><Lock size={16} /> Confirm Password</label>
              <input
                type="password"
                placeholder="Re-enter your password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(sanitizePasswordInput(e.target.value))}
                maxLength={128}
                autoComplete="new-password"
                aria-invalid={confirmMismatch}
                className={confirmMismatch ? styles.inputInvalid : undefined}
                disabled={loading || resending || challengeExpired}
                required
              />
            </div>
            <PasswordStrengthIndicator
              password={password}
              confirmPassword={confirmPassword}
            />
          </div>

          <button
            type="submit"
            className={styles.verifyBtn}
            disabled={loading || resending || challengeExpired || !canCreateAccount}
          >
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
          {exhausted ? (
            <p className={styles.resendHint}>No resends remaining</p>
          ) : (
            <p className={styles.resendHint}>
              {resendsRemaining} resend{resendsRemaining === 1 ? '' : 's'} remaining
              {cooldownSeconds > 0 ? ` · wait ${cooldownSeconds}s` : ''}
            </p>
          )}
        </div>
      </motion.div>
    </div>
  );
}
