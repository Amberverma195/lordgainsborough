'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { motion } from 'framer-motion';
import { ShieldCheck, ArrowRight, Mail } from 'lucide-react';
import styles from './verify.module.css';
import { persistAuth } from '@/lib/auth';
import { API_BASE } from '@/lib/orders';
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
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);
  const [syncing, setSyncing] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
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
    setCooldownSeconds(cooldownSecondsFromServerClocks(
      next.serverTime,
      next.nextResendAt,
      Date.now(),
      next.receivedAtMs
    ));
    if (broadcast) {
      publishChallenge(next, 'challenge-updated');
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
      setCooldownSeconds(cooldownSecondsFromServerClocks(
        next.serverTime,
        next.nextResendAt,
        Date.now(),
        next.receivedAtMs
      ));
      publishChallenge(next);

      if (body.code === 'OTP_DELIVERY_UNKNOWN') {
        setInfoMessage(
          body.message ||
          "We couldn't confirm whether the code was sent. Please check your inbox and spam folder. You can request another code when the timer ends."
        );
      } else if (body.code === 'OTP_CHALLENGE_EXPIRED') {
        setError(body.message || 'This verification session has expired. Please start signup again.');
        setSuccess('');
        setInfoMessage('');
      } else if (body.code === 'OTP_RESEND_LIMIT_REACHED') {
        setError("You've reached the resend limit. Please restart verification later.");
        setSuccess('');
        setInfoMessage('');
      }
    } catch {
      if (cached) {
        setChallenge(cached);
        setCooldownSeconds(cooldownSecondsFromServerClocks(
          cached.serverTime,
          cached.nextResendAt,
          Date.now(),
          cached.receivedAtMs
        ));
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
        router.replace('/profile');
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
      setCooldownSeconds(cooldownSecondsFromServerClocks(
        next.serverTime,
        next.nextResendAt,
        Date.now(),
        next.receivedAtMs
      ));
      if (next.code === 'OTP_CHALLENGE_EXPIRED') {
        setError(next.message || 'This verification session has expired. Please start signup again.');
      } else if (next.code === 'OTP_RESEND_LIMIT_REACHED') {
        setError("You've reached the resend limit. Please restart verification later.");
      } else if (next.code === 'OTP_DELIVERY_UNKNOWN') {
        setInfoMessage(
          next.message ||
          "We couldn't confirm whether the code was sent. Please check your inbox and spam folder. You can request another code when the timer ends."
        );
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
        setCooldownSeconds(cooldownSecondsFromServerClocks(
          cached.serverTime,
          cached.nextResendAt,
          Date.now(),
          cached.receivedAtMs
        ));
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
    setCooldownSeconds(cooldownSecondsFromServerClocks(
      challenge.serverTime,
      challenge.nextResendAt,
      nowTick,
      challenge.receivedAtMs
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
    if (cooldownSeconds > 0 || isResendExhausted(challenge) || isChallengeExpiredState(challenge, nowTick)) {
      return;
    }

    setResending(true);
    setError('');
    setSuccess('');
    setInfoMessage('');

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
        if (body.code === 'OTP_DELIVERY_UNKNOWN') {
          setInfoMessage(
            body.message ||
            "We couldn't confirm whether the code was sent. Please check your inbox and spam folder. You can request another code when the timer ends."
          );
        } else {
          setSuccess(body.message || 'A verification code has been sent.');
        }
        setOtp(['', '', '', '', '', '']);
        inputRefs.current[0]?.focus();
      } else if (res.status === 429) {
        if (body.code === 'OTP_RESEND_LIMIT_REACHED') {
          setError("You've reached the resend limit. Please restart verification later.");
        } else {
          setError(body.message || 'Please wait before requesting another code.');
        }
      } else if (res.status === 410) {
        setError(body.message || 'This verification session has expired. Please start signup again.');
      } else {
        setError(body.message || "We couldn't send your code. Please try again.");
      }
    } catch {
      setError('Unable to connect to the server. Please try again.');
    } finally {
      setResending(false);
    }
  };

  const handleCreateAccount = async (e: React.FormEvent) => {
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
    setSuccess('');
    setInfoMessage('');

    try {
      const res = await fetch(`${API_BASE}/auth/signup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: pendingSignup.email,
          otp: code,
          challengeId: challenge?.challengeId,
        }),
      });

      if (res.status === 201) {
        const data = await res.json();
        persistAuth(data);
        window.dispatchEvent(new Event('storage'));
        clearAllSignupSession();
        publishChallenge({
          email: pendingSignup.email,
          challengeId: challenge?.challengeId,
          code: 'OTP_VERIFIED',
        }, 'verified');
        router.push('/profile');
        return;
      }

      const body = await readSignupOtpResponse(res);
      applyChallengeFromBackend(body, pendingSignup.email);
      setError(body.message || 'Unable to complete verification. Please check the code and try again.');
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
  const canCreateAccount = otp.every(Boolean);

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
        {infoMessage && <div className={styles.infoBanner}>{infoMessage}</div>}
        {success && <div className={styles.successBanner}>{success}</div>}
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

          <button
            type="submit"
            className={styles.verifyBtn}
            disabled={loading || resending || challengeExpired || !canCreateAccount}
          >
            {loading ? 'Verifying...' : 'Verify Email & Create Account'} <ArrowRight size={18} />
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

          <button
            type="button"
            className={styles.backLink}
            onClick={() => {
              clearAllSignupSession();
              router.push('/signup');
            }}
          >
            Back to Signup / Change Email
          </button>
        </div>
      </motion.div>
    </div>
  );
}
