import {
  orderAlertsAvailable,
  enableOrderAlerts,
  disableOrderAlerts,
} from '../../notifications/orderAlerts';
import { useState } from 'react';
import { router } from 'expo-router';
import { Linking, StyleSheet, Text, View } from 'react-native';

import { getLegalDocumentUrl } from '../../api/legal';
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

  const [alertMessage, setAlertMessage] = useState('');
  const [alertBusy, setAlertBusy] = useState(false);
  async function alerts(enabled: boolean) {
    if (!user || alertBusy) return;
    setAlertBusy(true);
    try {
      if (enabled) await enableOrderAlerts(user.email, user.token);
      else await disableOrderAlerts(user.email, user.token);
      setAlertMessage(
        enabled
          ? 'Order alerts enabled for this device.'
          : 'Order alerts disabled for this device.',
      );
    } catch (failure) {
      setAlertMessage(
        failure instanceof Error
          ? failure.message
          : 'Unable to update order alerts.',
      );
    } finally {
      setAlertBusy(false);
    }
  }
  const [signingOut, setSigningOut] = useState(false);
  const [logoutError, setLogoutError] = useState('');
  async function logoutHere() {
    if (signingOut) return;
    setSigningOut(true);
    setLogoutError('');
    try {
      await signOut();
    } catch {
      setLogoutError(
        'Server sign-out could not be confirmed. Check your connection and try again.',
      );
    } finally {
      setSigningOut(false);
    }
  }
  return (
    <Screen scroll>
      <AppHeader title="Profile" eyebrow="Account" />
      {status === 'loading' ? (
        <LoadingState label="Restoring your session" />
      ) : null}
      {status === 'unreachable' ? (
        <ErrorState
          title="Session unavailable"
          message="We saved your sign-in on this device, but the restaurant could not be reached to confirm it."
          onRetry={() => void refresh()}
        />
      ) : null}
      {status === 'signedOut' ? (
        <View style={styles.gap}>
          <Text style={styles.copy}>
            Sign in or create an account with Lord Gainsborough.
          </Text>
          <Button label="Sign in" onPress={() => router.push('/auth/login')} />
          <Button
            label="Create account"
            variant="secondary"
            onPress={() => router.push('/auth/signup')}
          />
        </View>
      ) : null}
      {user ? (
        <View style={styles.gap}>
          <Card>
            <Text style={styles.name}>{user.name}</Text>
            <Text style={styles.copy}>{user.email}</Text>
            {user.phone ? <Text style={styles.copy}>{user.phone}</Text> : null}
            <Text style={styles.meta}>
              {roleLabel(user.role)}
              {user.memberSince ? ` · Member since ${user.memberSince}` : ''}
            </Text>
            {user.pickupDisabled ? (
              <Text style={styles.warning}>
                Pickup ordering is disabled on this account because of recent
                no-show history.
              </Text>
            ) : null}
          </Card>
          {user.role === 'ADMIN' || user.role === 'STAFF' ? (
            <Text style={styles.note}>
              The administrator dashboard and kitchen display stay on the
              website. This app is the customer pickup experience.
            </Text>
          ) : null}
          {orderAlertsAvailable ? (
            <>
              <Button
                label="Enable order alerts"
                loading={alertBusy}
                onPress={() => void alerts(true)}
              />
              <Button
                label="Disable order alerts"
                disabled={alertBusy}
                onPress={() => void alerts(false)}
              />
            </>
          ) : (
            <Text style={styles.copy}>
              Order push notifications are not enabled yet. Live order updates
              remain available in the app.
            </Text>
          )}
          {alertMessage ? (
            <Text accessibilityRole="alert" style={styles.copy}>
              {alertMessage}
            </Text>
          ) : null}
          <Button
            label="Edit profile"
            variant="secondary"
            onPress={() => router.push('/profile/edit')}
          />
          <Button
            label="View orders"
            variant="secondary"
            onPress={() => router.push('/(tabs)/orders')}
          />
          <Button
            label="Delete account"
            variant="ghost"
            onPress={() => router.push('/profile/delete')}
          />
          <Button
            label="Sign out"
            variant="ghost"
            loading={signingOut}
            onPress={() => void logoutHere()}
          />
        </View>
      ) : null}
      {logoutError ? (
        <Text accessibilityRole="alert" style={{ color: colors.danger }}>
          {logoutError}
        </Text>
      ) : null}
      <Button
        label="Terms of Service"
        variant="ghost"
        onPress={() => void Linking.openURL(getLegalDocumentUrl('/terms'))}
      />
      <Button
        label="Privacy Policy"
        variant="ghost"
        onPress={() => void Linking.openURL(getLegalDocumentUrl('/privacy'))}
      />
      <Button
        label="Support"
        variant="ghost"
        onPress={() => void Linking.openURL('mailto:dine@lordgainsborough.com')}
      />
      <Button
        label="Account deletion information"
        variant="ghost"
        onPress={() =>
          void Linking.openURL(getLegalDocumentUrl('/delete-account'))
        }
      />
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
