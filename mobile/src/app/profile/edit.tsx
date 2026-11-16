import { router } from 'expo-router';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';

import { updateProfile } from '../../api/auth';
import { ApiError } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { colors, radius, space, type } from '../../theme/tokens';

export default function ProfileEditScreen() {
  const { user, status, updateUser } = useAuth();
  const [name, setName] = useState(user?.name ?? '');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  if (status !== 'signedIn' || !user) {
    return (
      <Screen scroll>
        <AppHeader title="Edit profile" showBack />
        <Text style={styles.copy}>Sign in to edit your profile.</Text>
        <Button label="Sign in" onPress={() => router.push('/auth/login')} />
      </Screen>
    );
  }

  const handleSave = async () => {
    setError(null);
    const trimmed = name.trim();
    if (!trimmed) {
      setError('Please enter your full name.');
      return;
    }
    if (trimmed.length > 255) {
      setError('Name must not exceed 255 characters.');
      return;
    }

    setSaving(true);
    try {
      const updated = await updateProfile(user.token, trimmed);
      await updateUser(updated);
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace('/(tabs)/profile');
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Unable to update profile. Please check your connection.');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll>
        <AppHeader title="Edit profile" showBack showCart={false} />
        <Text style={styles.label}>Full name</Text>
        <TextInput
          value={name}
          onChangeText={(val) => {
            setName(val);
            setError(null);
          }}
          autoCapitalize="words"
          autoCorrect={false}
          textContentType="name"
          accessibilityLabel="Full name"
          placeholder="Your full name"
          placeholderTextColor={colors.textMuted}
          style={styles.input}
        />

        <Card style={styles.readOnlyCard}>
          <Text style={styles.readOnlyLabel}>Email address</Text>
          <Text style={styles.readOnlyValue}>{user.email}</Text>
          <Text style={styles.note}>Email is tied to your account and cannot be modified.</Text>

          {user.phone ? (
            <>
              <Text style={[styles.readOnlyLabel, { marginTop: space.md }]}>Phone number</Text>
              <Text style={styles.readOnlyValue}>{user.phone}</Text>
              <Text style={styles.note}>Phone number is verified and tied to pickup security.</Text>
            </>
          ) : null}
        </Card>

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <View style={styles.actions}>
          <Button label="Save changes" loading={saving} onPress={() => void handleSave()} />
          <Button label="Cancel" variant="ghost" disabled={saving} onPress={() => router.back()} />
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
  readOnlyCard: {
    marginBottom: space.xl,
  },
  readOnlyLabel: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: space.xs,
  },
  readOnlyValue: {
    color: colors.text,
    fontFamily: type.body,
    fontSize: 16,
    marginBottom: space.xs,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 13,
    lineHeight: 18,
  },
  error: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    marginBottom: space.md,
  },
  actions: {
    gap: space.md,
    marginTop: space.md,
  },
});
