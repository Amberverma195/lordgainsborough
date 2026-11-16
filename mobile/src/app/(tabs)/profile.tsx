import { router } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { useAuth } from '../../auth/AuthContext';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { ErrorState, LoadingState } from '../../components/States';
import { roleLabel } from '../../format/display';
import { colors, space, type } from '../../theme/tokens';

export default function ProfileScreen() {
  const { status, user, signOut, refresh } = useAuth();

  return (
    <Screen scroll>
      <AppHeader title="Profile" eyebrow="Account" />
      {status === 'loading' ? <LoadingState label="Restoring your session" /> : null}
      {status === 'unreachable' ? (
        <ErrorState
          title="Session unavailable"
          message="We saved your sign-in on this device, but the restaurant could not be reached to confirm it."
          onRetry={() => void refresh()}
        />
      ) : null}
      {status === 'signedOut' ? (
        <View style={styles.gap}>
          <Text style={styles.copy}>Sign in with the email or phone number on your Lord Gainsborough account.</Text>
          <Button label="Sign in" onPress={() => router.push('/auth/login')} />
          <Text style={styles.note}>
            Creating an account and resetting a password use the restaurant’s email verification.
            The mobile screens for those flows are prepared for the next release and are not available yet.
          </Text>
        </View>
      ) : null}
      {user ? (
        <View style={styles.gap}>
          <Card>
            <Text style={styles.name}>{user.name}</Text>
            <Text style={styles.copy}>{user.email}</Text>
            {user.phone ? <Text style={styles.copy}>{user.phone}</Text> : null}
            <Text style={styles.meta}>{roleLabel(user.role)}{user.memberSince ? ` · Member since ${user.memberSince}` : ''}</Text>
            {user.pickupDisabled ? (
              <Text style={styles.warning}>Pickup ordering is disabled on this account because of recent no-show history.</Text>
            ) : null}
          </Card>
          {user.role === 'ADMIN' || user.role === 'STAFF' ? (
            <Text style={styles.note}>
              The administrator dashboard and kitchen display stay on the website. This app is the customer pickup experience.
            </Text>
          ) : null}
          <Button label="View orders" variant="secondary" onPress={() => router.push('/(tabs)/orders')} />
          <Button label="Sign out" variant="ghost" onPress={() => void signOut()} />
        </View>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: {
    gap: space.md,
  },
  name: {
    color: colors.text,
    fontFamily: type.display,
    fontSize: 32,
    marginBottom: space.sm,
  },
  copy: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
  },
  meta: {
    color: colors.gold,
    fontFamily: type.label,
    marginTop: space.md,
  },
  warning: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    marginTop: space.md,
    lineHeight: 22,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    lineHeight: 22,
  },
});
