import * as Crypto from 'expo-crypto';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { sendSignupOtp, verifySignup } from '../../api/auth';
import { ApiError } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { isValidEmail, isValidPhone, normalizePhone, validatePassword } from '../../auth/validation';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Screen } from '../../components/Screen';
import { colors, radius, space, type } from '../../theme/tokens';

export default function SignupScreen() {
  const { establishSession } = useAuth();
  const [step, setStep] = useState<'details' | 'verify'>('details');

  // Step 1: Details
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  // Step 2: Verification
  const [otp, setOtp] = useState('');
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [attemptToken, setAttemptToken] = useState<string | null>(null);
  const [cooldown, setCooldown] = useState(0);

  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);

  // Cooldown countdown timer
  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setInterval(() => {
      setCooldown((prev) => (prev > 0 ? prev - 1 : 0));
    }, 1000);
    return () => clearInterval(timer);
  }, [cooldown]);

  const handleSendOtp = async () => {
    setError(null);
    const cleanName = name.trim();
    if (!cleanName) {
      setError('Full name is required.');
      return;
    }
    if (!isValidPhone(phone)) {
      setError('Please enter a valid 10-digit phone number.');
      return;
    }
    const cleanEmail = email.trim().toLowerCase();
    if (!isValidEmail(cleanEmail)) {
      setError('Please enter a valid email address.');
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
      const result = await sendSignupOtp({
        name: cleanName,
        phone: normalizePhone(phone),
        email: cleanEmail,
        password,
        idempotencyKey,
        attemptToken,
      });

      setChallengeId(result.challengeId);
      if (result.attemptToken) {
        setAttemptToken(result.attemptToken);
      }
      setCooldown(60);
      setStep('verify');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Unable to send verification code. Please check your connection.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleVerifyOtp = async () => {
    setError(null);
    const cleanOtp = otp.trim().replace(/\D/g, '');
    if (cleanOtp.length !== 6) {
      setError('Please enter the 6-digit verification code.');
      return;
    }

    setSubmitting(true);
    try {
      const sessionUser = await verifySignup({
        name: name.trim(),
        phone: normalizePhone(phone),
        email: email.trim().toLowerCase(),
        password,
        otp: cleanOtp,
        challengeId,
        attemptToken,
      });

      await establishSession(sessionUser);
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace('/(tabs)/profile');
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Verification failed. Please check your code and try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleResend = async () => {
    if (cooldown > 0 || resending) return;
    setError(null);
    setResending(true);
    try {
      const idempotencyKey = Crypto.randomUUID();
      const result = await sendSignupOtp({
        name: name.trim(),
        phone: normalizePhone(phone),
        email: email.trim().toLowerCase(),
        password,
        idempotencyKey,
        attemptToken,
        restart: false,
      });
      setChallengeId(result.challengeId);
      if (result.attemptToken) {
        setAttemptToken(result.attemptToken);
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
          title={step === 'details' ? 'Create account' : 'Verify email'}
          showBack
          showCart={false}
          onBack={step === 'verify' ? () => setStep('details') : undefined}
        />

        {step === 'details' ? (
          <View style={styles.form}>
            <Text style={styles.copy}>Join Lord Gainsborough for pickup orders and member updates.</Text>

            <Text style={styles.label}>Full name</Text>
            <TextInput
              value={name}
              onChangeText={(val) => {
                setName(val);
                setError(null);
              }}
              autoCapitalize="words"
              textContentType="name"
              accessibilityLabel="Full name"
              placeholder="Jane Doe"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            <Text style={styles.label}>Phone number</Text>
            <TextInput
              value={phone}
              onChangeText={(val) => {
                setPhone(val);
                setError(null);
              }}
              keyboardType="phone-pad"
              textContentType="telephoneNumber"
              accessibilityLabel="Phone number"
              placeholder="519-555-0100"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

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

            <Text style={styles.label}>Password</Text>
            <TextInput
              value={password}
              onChangeText={(val) => {
                setPassword(val);
                setError(null);
              }}
              secureTextEntry
              textContentType="newPassword"
              accessibilityLabel="Password"
              placeholder="At least 8 chars, 1 upper, 1 number, 1 special"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            <Text style={styles.label}>Confirm password</Text>
            <TextInput
              value={confirmPassword}
              onChangeText={(val) => {
                setConfirmPassword(val);
                setError(null);
              }}
              secureTextEntry
              textContentType="newPassword"
              accessibilityLabel="Confirm password"
              placeholder="Repeat password"
              placeholderTextColor={colors.textMuted}
              style={styles.input}
            />

            {error ? <Text style={styles.error}>{error}</Text> : null}

            <Button label="Continue" loading={submitting} onPress={() => void handleSendOtp()} />

            <View style={styles.switchRow}>
              <Text style={styles.copySecondary}>Already have an account?</Text>
              <Pressable accessibilityRole="button" onPress={() => router.push('/auth/login')}>
                <Text style={styles.linkText}>Sign in</Text>
              </Pressable>
            </View>
          </View>
        ) : (
          <View style={styles.form}>
            <Text style={styles.copy}>
              We sent a 6-digit verification code to <Text style={styles.highlight}>{email.trim()}</Text>.
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

            {error ? <Text style={styles.error}>{error}</Text> : null}

            <Button label="Verify & create account" loading={submitting} onPress={() => void handleVerifyOtp()} />

            <View style={styles.resendWrap}>
              {cooldown > 0 ? (
                <Text style={styles.cooldownText}>Resend code in {cooldown}s</Text>
              ) : (
                <Pressable accessibilityRole="button" onPress={() => void handleResend()} disabled={resending}>
                  <Text style={styles.linkText}>{resending ? 'Sending…' : 'Resend code'}</Text>
                </Pressable>
              )}
            </View>

            <Button
              label="Edit registration details"
              variant="ghost"
              disabled={submitting}
              onPress={() => setStep('details')}
            />
          </View>
        )}
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
  copySecondary: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 14,
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
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: space.xs,
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
