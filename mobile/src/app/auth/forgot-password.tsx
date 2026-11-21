import { onAccountChange } from '../../auth/lifecycle';
import * as Crypto from 'expo-crypto';
import { router, useFocusEffect } from 'expo-router';
import { useEffect, useState, useRef, useCallback } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import {
  completePasswordReset,
  resendPasswordResetOtp,
  sendPasswordResetOtp,
} from '../../api/auth';
import { ApiError } from '../../api/errors';
import {
  isValidEmail,
  isValidPhone,
  normalizePhone,
  validatePassword,
} from '../../auth/validation';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Screen } from '../../components/Screen';
import { colors, radius, space, type } from '../../theme/tokens';

export default function ForgotPasswordScreen() {
  const [step, setStep] = useState<'email' | 'reset' | 'complete'>('email');

  const [email, setEmail] = useState('');
  const [otp, setOtp] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [challengeId, setChallengeId] = useState<string | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);
  const [cooldown, setCooldown] = useState(0);

  const active = useRef(true);
  const generation = useRef(0);
  const locked = useRef(false);
  const [deadline, setDeadline] = useState(0);
  const [expires, setExpires] = useState(0);
  const [codeExpires, setCodeExpires] = useState(0);
  const [codeExpired, setCodeExpired] = useState(false);
  const [quota, setQuota] = useState<number | null>(null);
  const [expired, setExpired] = useState(false);
  useFocusEffect(
    useCallback(() => {
      active.current = true;
      return () => {
        active.current = false;
        generation.current++;
        locked.current = false;
        setOtp('');
        setPassword('');
        setConfirmPassword('');
        setSubmitting(false);
        setResending(false);
      };
    }, []),
  );
  useEffect(() => {
    const tick = () => {
      setCodeExpired(!!codeExpires && Date.now() >= codeExpires);
      setCooldown(Math.max(0, Math.ceil((deadline - Date.now()) / 1000)));
      if (expires && Date.now() >= expires) setExpired(true);
    };
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [deadline, expires, codeExpires]);
  function accept(result: import('../../api/auth').PasswordResetResult) {
    const offset = result.serverTime
      ? Date.now() - Date.parse(result.serverTime)
      : 0;
    setDeadline(
      result.nextResendAt
        ? Date.parse(result.nextResendAt) + offset
        : Date.now() + (result.retryAfterSeconds ?? 0) * 1000,
    );
    setExpires(
      result.challengeExpiresAt
        ? Date.parse(result.challengeExpiresAt) + offset
        : 0,
    );
    setCodeExpires(
      result.codeExpiresAt ? Date.parse(result.codeExpiresAt) + offset : 0,
    );
    setQuota(result.resendsRemaining ?? null);
    if (result.code === 'RESET_CHALLENGE_REUSED') setError(result.message);
  }
  function failed(err: unknown) {
    if (err instanceof ApiError) {
      setError(err.message);
      if (err.retryAfterSeconds)
        setDeadline(Date.now() + err.retryAfterSeconds * 1000);
      if (err.code === 'RESET_CHALLENGE_EXPIRED' || err.status === 410)
        setExpired(true);
    }
  }
  useEffect(
    () =>
      onAccountChange(() => {
        generation.current++;
        active.current = false;
        setOtp('');
        setPassword('');
        setConfirmPassword('');
        setStep('email');
        setChallengeId(null);
      }),
    [],
  );
  function restart() {
    generation.current++;
    locked.current = false;
    setStep('email');
    setChallengeId(null);
    setExpired(false);
    setOtp('');
    setPassword('');
    setConfirmPassword('');
    setError(null);
    setSubmitting(false);
    setResending(false);
  }

  const handleSendResetCode = async () => {
    setError(null);
    const cleanEmail = email.includes('@')
      ? email.trim().toLowerCase()
      : normalizePhone(email);
    if (!isValidEmail(cleanEmail) && !isValidPhone(cleanEmail)) {
      setError('Please enter a valid email or Canadian/US phone number.');
      return;
    }

    if (locked.current) return;
    active.current = true;
    locked.current = true;
    const ticket = generation.current;
    setSubmitting(true);
    try {
      const idempotencyKey = Crypto.randomUUID();
      const result = await sendPasswordResetOtp(cleanEmail, idempotencyKey);
      if (!active.current || ticket !== generation.current) return;
      if (result.challengeId) {
        setChallengeId(result.challengeId);
      }
      accept(result);
      setStep('reset');
    } catch (err) {
      if (!active.current || ticket !== generation.current) return;
      failed(err);
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Unable to send reset code. Please check your connection.');
      }
    } finally {
      if (ticket === generation.current) {
        locked.current = false;
        setSubmitting(false);
      }
    }
  };

  const handleResetPassword = async () => {
    setError(null);
    const cleanOtp = otp.trim().replace(/\D/g, '');
    if (cleanOtp.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }
    const passwordCheck = validatePassword(password);
    if (!passwordCheck.valid) {
      setError(
        passwordCheck.message ??
          'Password does not meet complexity requirements.',
      );
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    if (locked.current) return;
    locked.current = true;
    const ticket = generation.current;
    setSubmitting(true);
    try {
      const idempotencyKey = Crypto.randomUUID();
      await completePasswordReset({
        email: email.trim().toLowerCase(),
        challengeId: challengeId ?? '',
        otp: cleanOtp,
        password,
        idempotencyKey,
      });
      if (!active.current || ticket !== generation.current) return;
      setPassword('');
      setConfirmPassword('');
      setOtp('');
      setStep('complete');
    } catch (err) {
      if (!active.current || ticket !== generation.current) return;
      failed(err);
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(
          'Failed to reset password. Please check your code and try again.',
        );
      }
    } finally {
      if (ticket === generation.current) {
        locked.current = false;
        setSubmitting(false);
      }
    }
  };

  const handleResend = async () => {
    if (
      cooldown > 0 ||
      resending ||
      locked.current ||
      expired ||
      quota === 0 ||
      !challengeId
    )
      return;
    setError(null);
    locked.current = true;
    const ticket = generation.current;
    setResending(true);
    try {
      const idempotencyKey = Crypto.randomUUID();
      const result = await resendPasswordResetOtp(
        email.trim().toLowerCase(),
        challengeId,
        idempotencyKey,
      );
      if (!active.current || ticket !== generation.current) return;
      if (result.challengeId) {
        setChallengeId(result.challengeId);
      }
      accept(result);
    } catch (err) {
      if (!active.current || ticket !== generation.current) return;
      failed(err);
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Unable to resend code. Please try again.');
      }
    } finally {
      if (ticket === generation.current) {
        locked.current = false;
        setResending(false);
      }
    }
  };

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <Screen scroll>
        <AppHeader
          title={step === 'complete' ? 'Reset complete' : 'Reset password'}
          showBack={step !== 'complete'}
          showCart={false}
          onBack={step === 'reset' ? restart : undefined}
        />

        {step === 'email' ? (
          <View style={styles.form}>
            <Text style={styles.copy}>
              Enter your registered email or Canadian/US telephone number. Codes
              are delivered to the account email.
            </Text>

            <Text style={styles.label}>Email address or phone number</Text>
            <TextInput
              editable={!submitting}
              value={email}
              onChangeText={(val) => {
                generation.current++;
                setEmail(val);
                setError(null);
              }}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="email-address"
              textContentType="emailAddress"
              accessibilityLabel="Email address or phone number"
              placeholder="name@email.com"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            {error ? <Text style={styles.error}>{error}</Text> : null}

            <Button
              label="Send reset code"
              disabled={cooldown > 0}
              loading={submitting}
              onPress={() => void handleSendResetCode()}
            />

            <View style={styles.switchRow}>
              <Pressable
                accessibilityRole="button"
                onPress={() => router.back()}
              >
                <Text style={styles.linkText}>Back to sign in</Text>
              </Pressable>
            </View>
          </View>
        ) : null}

        {step === 'reset' ? (
          <View style={styles.form}>
            <Text style={styles.copy}>
              Enter the code sent to{' '}
              <Text style={styles.highlight}>{email.trim()}</Text> and choose a
              new password.
            </Text>

            <Text style={styles.label}>Verification code</Text>
            <TextInput
              value={otp}
              onChangeText={(val) => {
                setOtp(val);
                setError(null);
              }}
              keyboardType="number-pad"
              maxLength={6}
              autoFocus
              accessibilityLabel="6-digit verification code"
              placeholder="123456"
              placeholderTextColor={colors.textMuted}
              style={[styles.input, styles.otpInput]}
            />

            <Text style={styles.label}>New password</Text>
            <TextInput
              value={password}
              onChangeText={(val) => {
                setPassword(val);
                setError(null);
              }}
              secureTextEntry
              textContentType="newPassword"
              accessibilityLabel="New password"
              placeholder="At least 8 chars, 1 upper, 1 number, 1 special"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            <Text style={styles.label}>Confirm new password</Text>
            <TextInput
              value={confirmPassword}
              onChangeText={(val) => {
                setConfirmPassword(val);
                setError(null);
              }}
              secureTextEntry
              textContentType="newPassword"
              accessibilityLabel="Confirm new password"
              placeholder="Repeat new password"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            {error ? <Text style={styles.error}>{error}</Text> : null}

            <Button
              label="Reset password"
              disabled={expired || codeExpired}
              loading={submitting}
              onPress={() => void handleResetPassword()}
            />

            <Text style={styles.copy}>
              {quota === null
                ? 'Resend allowance is controlled by the restaurant.'
                : quota + ' resends remaining'}
            </Text>
            {expired ? (
              <Button label="Start New Reset" onPress={restart} />
            ) : null}
            {codeExpired ? (
              <Text style={styles.error}>
                This code has expired. Request another code.
              </Text>
            ) : null}
            <View style={styles.resendWrap}>
              {cooldown > 0 ? (
                <Text style={styles.cooldownText}>
                  Resend code in {cooldown}s
                </Text>
              ) : (
                <Pressable
                  accessibilityRole="button"
                  onPress={() => void handleResend()}
                  disabled={resending || expired || quota === 0}
                >
                  <Text style={styles.linkText}>
                    {resending ? 'Sending…' : 'Resend code'}
                  </Text>
                </Pressable>
              )}
            </View>
          </View>
        ) : null}

        {step === 'complete' ? (
          <View style={styles.form}>
            <Text style={styles.successHeading}>Password updated</Text>
            <Text style={styles.copy}>
              Your password has been successfully reset. You can now sign in
              with your new credentials.
            </Text>
            <Button
              label="Sign in"
              onPress={() => router.replace('/auth/login')}
            />
          </View>
        ) : null}
      </Screen>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
    backgroundColor: colors.background,
  },
  form: {
    gap: space.xs,
  },
  copy: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
    marginBottom: space.lg,
  },
  successHeading: {
    color: colors.gold,
    fontFamily: type.display,
    fontSize: 28,
    marginBottom: space.sm,
  },
  highlight: {
    color: colors.gold,
    fontFamily: type.bodyMedium,
  },
  label: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: space.xs,
  },
  input: {
    minHeight: 52,
    borderWidth: 1,
    borderColor: colors.goldBorder,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    color: colors.text,
    fontFamily: type.body,
    fontSize: 16,
    marginBottom: space.md,
  },
  otpInput: {
    textAlign: 'center',
    fontSize: 24,
    letterSpacing: 8,
    fontFamily: type.heading,
  },
  error: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    marginBottom: space.md,
  },
  switchRow: {
    alignItems: 'center',
    marginTop: space.lg,
  },
  linkText: {
    color: colors.gold,
    fontFamily: type.bodyMedium,
    fontSize: 14,
  },
  resendWrap: {
    alignItems: 'center',
    marginVertical: space.md,
  },
  cooldownText: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 14,
  },
});
