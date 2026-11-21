import { useState, useRef, useEffect } from 'react';
import { Text, TextInput } from 'react-native';
import { router } from 'expo-router';
import { deleteAccount } from '../../api/auth';
import { ApiError } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { clearDeletedAccountData } from '../../auth/deleteAccountData';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Screen } from '../../components/Screen';
import { colors } from '../../theme/tokens';

export default function DeleteAccountScreen() {
  const { user, signOut } = useAuth();
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);
  const current = useRef(user);
  useEffect(() => {
    current.current = user;
  }, [user]);
  async function remove() {
    if (!user || !password || locked.current) return;
    const account = user;
    locked.current = true;
    setBusy(true);
    setError('');
    let deleted = false;
    try {
      await deleteAccount(account.token, password);
      deleted = true;
      setPassword('');
      if (current.current?.token !== account.token) return;
      try {
        await clearDeletedAccountData(account.email);
      } finally {
        await signOut();
      }
      await clearDeletedAccountData(account.email);
      router.replace('/(tabs)/profile');
    } catch (failure) {
      setPassword('');
      setError(
        deleted
          ? "Your account was deleted. Device cleanup failed. Clear this app's data before sharing this device."
          : failure instanceof ApiError
            ? failure.code === 'ACCOUNT_HAS_ACTIVE_ORDERS'
              ? 'Please complete or resolve your active orders before deleting your account.'
              : failure.status === 401
                ? 'Your session expired. Sign in again.'
                : failure.status === 429
                  ? 'Too many attempts. Try again in ' +
                    (failure.retryAfterSeconds ?? 60) +
                    ' seconds.'
                  : failure.message
            : 'Unable to reach the restaurant. Check your connection.',
      );
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  return (
    <Screen scroll>
      <AppHeader title="Delete account" showBack />
      <Text style={{ color: colors.text }}>
        Permanently delete your Lord Gainsborough account and associated
        personal data. This cannot be undone. Active orders must be resolved
        first. Records required by law may be retained as described in our
        Privacy Policy.
      </Text>
      {user ? (
        <>
          <TextInput
            accessibilityLabel="Current password"
            placeholder="Current password"
            placeholderTextColor={colors.textMuted}
            style={{ color: colors.text, minHeight: 56 }}
            secureTextEntry
            value={password}
            onChangeText={setPassword}
            editable={!busy}
            maxLength={128}
          />
          <Button
            label="Permanently delete my account"
            loading={busy}
            disabled={!password || busy}
            onPress={() => void remove()}
          />
        </>
      ) : (
        <Button label="Sign in" onPress={() => router.replace('/auth/login')} />
      )}
      {error ? (
        <Text accessibilityRole="alert" style={{ color: colors.danger }}>
          {error}
        </Text>
      ) : null}
    </Screen>
  );
}
