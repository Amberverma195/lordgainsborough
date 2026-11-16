import * as Crypto from 'expo-crypto';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { completePasswordReset, resendPasswordResetOtp, sendPasswordResetOtp } from '../../api/auth';
import { ApiError } from '../../api/errors';
import { isValidEmail, validatePassword } from '../../auth/validation';
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

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setInterval(() => {
      setCooldown((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(timer);
  }, [cooldown]);

  const handleSendResetCode = async () => {
    setError(null);
    const cleanEmail = email.trim().toLowerCase();
    if (!isValidEmail(cleanEmail)) {
      setError('Please enter a valid email address.');
      return;
    }

    setSubmitting(true);
    try {
      const idempotencyKey = Crypto.randomUUID();
      const result = await sendPasswordResetOtp(cleanEmail, idempotencyKey);
      if (result.challengeId) {
        setChallengeId(result.challengeId);
      }
      setCooldown(60);
      setStep('reset');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Unable to send reset code. Please check your connection.');
      }
    } finally {
      setSubmitting(false);
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
      setError(passwordCheck.message ?? 'Password does not meet complexity requirements.');
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

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
      setStep('complete');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Failed to reset password. Please check your code and try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleResend = async () => {
    if (cooldown > 0 || resending || !challengeId) return;
    setError(null);
    setResending(true);
    try {
      const idempotencyKey = Crypto.randomUUID();
      const result = await resendPasswordResetOtp(email.trim().toLowerCase(), challengeId, idempotencyKey);
      if (result.challengeId) {
        setChallengeId(result.challengeId);
      }
      setCooldown(60);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Unable to resend code. Please try again.');
      }
    } finally {
      setResending(false);
    }
  };

  return (
    <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll>
        <AppHeader
          title={step === 'complete' ? 'Reset complete' : 'Reset password'}
          showBack={step !== 'complete'}
          showCart={false}
          onBack={step === 'reset' ? () => setStep('email') : undefined}
        />

        {step === 'email' ? (
          <View style={styles.form}>
            <Text style={styles.copy}>
              Enter the email address registered with your account to receive a reset code.
            </Text>

            <Text style={styles.label}>Email address</Text>
            <TextInput
              value={email}
              onChangeText={(val) => {
                setEmail(val);
                setError(null);
              }}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="email-address"
              textContentType="emailAddress"
              accessibilityLabel="Email address"
              placeholder="name@email.com"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            {error ? <Text style={styles.error}>{error}</Text> : null}

            <Button label="Send reset code" loading={submitting} onPress={() => void handleSendResetCode()} />

            <View style={styles.switchRow}>
              <Pressable accessibilityRole="button" onPress={() => router.back()}>
                <Text style={styles.linkText}>Back to sign in</Text>
              </Pressable>
            </View>
          </View>
        ) : null}

        {step === 'reset' ? (
          <View style={styles.form}>
            <Text style={styles.copy}>
              Enter the code sent to <Text style={styles.highlight}>{email.trim()}</Text> and choose a new password.
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

            <Button label="Reset password" loading={submitting} onPress={() => void handleResetPassword()} />

            <View style={styles.resendWrap}>
              {cooldown > 0 ? (
                <Text style={styles.cooldownText}>Resend code in {cooldown}s</Text>
              ) : (
                <Pressable accessibilityRole="button" onPress={() => void handleResend()} disabled={resending}>
                  <Text style={styles.linkText}>{resending ? 'Sending…' : 'Resend code'}</Text>
                </Pressable>
              )}
            </View>
          </View>
        ) : null}

        {step === 'complete' ? (
          <View style={styles.form}>
            <Text style={styles.successHeading}>Password updated</Text>
            <Text style={styles.copy}>
              Your password has been successfully reset. You can now sign in with your new credentials.
            </Text>
            <Button label="Sign in" onPress={() => router.replace('/auth/login')} />
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
