import { router } from 'expo-router';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';

import { ApiError } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { loginFailureMessage } from '../../auth/session';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Screen } from '../../components/Screen';
import { colors, radius, space, type } from '../../theme/tokens';

export default function LoginScreen() {
  const { signIn } = useAuth();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setError(null);
    if (!identifier.trim() || !password) {
      setError('Email or phone number and password are required.');
      return;
    }
    setSubmitting(true);
    try {
      await signIn(identifier, password);
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace('/(tabs)');
      }
    } catch (requestError) {
      setError(requestError instanceof ApiError ? loginFailureMessage(requestError) : loginFailureMessage(requestError));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll>
        <AppHeader title="Sign in" showBack showCart={false} />
        <Text style={styles.copy}>Use the email address or phone number on your Lord Gainsborough account.</Text>
        <Text style={styles.label}>Email or phone</Text>
        <TextInput
          value={identifier}
          onChangeText={setIdentifier}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="email-address"
          textContentType="username"
          accessibilityLabel="Email or phone"
          placeholder="name@email.com"
          placeholderTextColor={colors.textMuted}
          style={styles.input}
        />
        <Text style={styles.label}>Password</Text>
        <TextInput
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          textContentType="password"
          accessibilityLabel="Password"
          placeholder="Password"
          placeholderTextColor={colors.textMuted}
          style={styles.input}
        />
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <Button label="Sign in" loading={submitting} onPress={() => void submit()} />
        <View style={styles.noteWrap}>
          <Text style={styles.note}>
            Account creation and password reset stay on the restaurant website for this release.
            The app already knows the email verification endpoints and will use them in the next milestone.
          </Text>
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
    backgroundColor: colors.background,
  },
  copy: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
    marginBottom: space.lg,
  },
  label: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: space.sm,
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
    marginBottom: space.lg,
  },
  error: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    marginBottom: space.md,
  },
  noteWrap: {
    marginTop: space.xl,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    lineHeight: 22,
  },
});
