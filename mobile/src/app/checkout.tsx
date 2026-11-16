import AsyncStorage from '@react-native-async-storage/async-storage';
import { useQuery } from '@tanstack/react-query';
import * as Crypto from 'expo-crypto';
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, Text, TextInput, View } from 'react-native';

import { fetchStoreSettings } from '../api/catalog';
import { createOrder } from '../api/orders';
import { ApiError } from '../api/errors';
import { useAuth } from '../auth/AuthContext';
import { writeTrackingToken } from '../auth/secureSession';
import { useCart } from '../cart/CartContext';
import { formatMoney } from '../cart/money';
import { rememberConfirmedOrder } from '../checkout/confirmationCache';
import { checkoutFingerprint, clearCheckoutIdempotencyKey, createSubmitGuard, getOrCreateCheckoutIdempotencyKey } from '../checkout/idempotency';
import { shouldClearIdempotencyKey, validateCheckout } from '../checkout/validate';
import { AppHeader } from '../components/AppHeader';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { PickupPicker } from '../components/PickupPicker';
import { Price } from '../components/Price';
import { Screen } from '../components/Screen';
import { ErrorState, LoadingState } from '../components/States';
import { restaurantFacts } from '../restaurant/facts';
import { colors, radius, space, type } from '../theme/tokens';

const storage = {
  getItem: (key: string) => AsyncStorage.getItem(key),
  setItem: (key: string, value: string) => AsyncStorage.setItem(key, value),
  removeItem: (key: string) => AsyncStorage.removeItem(key),
  getAllKeys: () => AsyncStorage.getAllKeys(),
};

export default function CheckoutScreen() {
  const { user, status } = useAuth();
  const { lines, pickupDate, pickupTime, setPickup, clear, estimate } = useCart();
  const settings = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => fetchStoreSettings(signal) });
  const [instructions, setInstructions] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const guard = useRef(createSubmitGuard()).current;
  const taxRate = settings.data?.taxRate;
  const totals = estimate(taxRate ?? 0);

  const placeOrder = async () => {
    if (!guard.begin()) {
      return;
    }
    setSubmitting(true);
    setError(null);
    let confirmed = false;
    try {
      if (!settings.data || taxRate === undefined) {
        setError('Restaurant settings could not be loaded, so tax cannot be shown yet.');
        return;
      }
      const problem = validateCheckout({
        signedIn: status === 'signedIn' && !!user,
        pickupDisabled: user?.pickupDisabled ?? false,
        pickupOrdersEnabled: settings.data.pickupOrdersEnabled,
        name: user?.name ?? '',
        email: user?.email ?? '',
        phone: user?.phone ?? '',
        lines,
        pickupDate,
        pickupTime,
        specialInstructions: instructions,
        taxRate,
      });
      if (problem || !user) {
        setError(problem?.message ?? 'Sign in to place a pickup order.');
        return;
      }
      const idempotencyKey = await getOrCreateCheckoutIdempotencyKey(
        storage,
        checkoutFingerprint({
          lines,
          pickupDate,
          pickupTime,
          specialInstructions: instructions,
        }),
        () => Crypto.randomUUID(),
        user.email,
      );
      const created = await createOrder(user.token, {
        customerName: user.name,
        customerEmail: user.email,
        customerPhone: user.phone,
        pickupDate,
        pickupTime,
        specialInstructions: instructions,
        averageReadyMinutes: settings.data.defaultReadyMinutes,
        idempotencyKey,
        lines,
        subtotal: totals.subtotal,
        tax: totals.tax,
        total: totals.total,
      });
      rememberConfirmedOrder(created.order);
      if (created.trackingToken) {
        try {
          await writeTrackingToken(created.order.id, created.trackingToken);
        } catch {
          // The order is already confirmed. A missing local recovery token can be replaced by the account session.
        }
      }
      await clearCheckoutIdempotencyKey(storage, user.email);
      clear();
      confirmed = true;
      router.replace(`/order/${created.order.id}?confirmed=1`);
    } catch (requestError) {
      if (requestError instanceof ApiError && shouldClearIdempotencyKey(requestError.code)) {
        await clearCheckoutIdempotencyKey(storage, user?.email);
      }
      setError(requestError instanceof ApiError
        ? requestError.message
        : 'We could not confirm the order. Please try again.');
    } finally {
      if (!confirmed) {
        guard.end();
        setSubmitting(false);
      }
    }
  };

  if (lines.length === 0) {
    return (
      <Screen>
        <AppHeader title="Checkout" showBack showCart={false} />
        <Text style={styles.copy}>Your cart is empty, so there is nothing to send to the kitchen.</Text>
        <Button label="Return to menu" onPress={() => router.replace('/(tabs)/menu')} />
      </Screen>
    );
  }

  return (
    <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll>
        <AppHeader title="Checkout" showBack cartCount={lines.reduce((sum, line) => sum + line.quantity, 0)} />
        {settings.isLoading ? <LoadingState label="Loading restaurant settings" /> : null}
        {settings.isError ? (
          <ErrorState title="Settings unavailable" message="Pickup tax and availability could not be loaded." onRetry={() => void settings.refetch()} />
        ) : null}
        <Card>
          <Text style={styles.heading}>Review</Text>
          {lines.map((line) => (
            <View key={line.menuItemId} style={styles.line}>
              <Text style={styles.copy}>{line.quantity} × {line.name}</Text>
              <Text style={styles.copy}>{formatMoney(line.unitPrice * line.quantity)}</Text>
            </View>
          ))}
          <View style={styles.line}>
            <Text style={styles.copy}>Estimated tax{taxRate !== undefined ? ` (${(taxRate * 100).toFixed(1)}%)` : ''}</Text>
            <Price amount={taxRate === undefined ? 0 : totals.tax} muted={taxRate === undefined} />
          </View>
          <View style={styles.line}>
            <Text style={styles.totalLabel}>Estimated total</Text>
            <Price amount={taxRate === undefined ? totals.subtotal : totals.total} />
          </View>
          <Text style={styles.note}>The restaurant recalculates prices, tax, and the total when the order is accepted.</Text>
        </Card>
        <PickupPicker date={pickupDate} time={pickupTime} onChange={setPickup} />
        <Text style={styles.label}>Special instructions</Text>
        <TextInput
          value={instructions}
          onChangeText={setInstructions}
          maxLength={500}
          multiline
          placeholder="Allergies, preferences, or notes for the kitchen"
          placeholderTextColor={colors.textMuted}
          style={styles.input}
          accessibilityLabel="Special instructions"
        />
        <Card>
          <Text style={styles.note}>{restaurantFacts.paymentNote}</Text>
        </Card>
        {settings.data && !settings.data.pickupOrdersEnabled ? (
          <Text style={styles.error}>Pickup orders are currently paused. Please check back later.</Text>
        ) : null}
        {user?.pickupDisabled ? <Text style={styles.error}>Pickup disabled due to recent no-show history</Text> : null}
        {error ? <Text style={styles.error}>{error}</Text> : null}
        {status !== 'signedIn' ? (
          <Button label="Sign in to order" onPress={() => router.push('/auth/login')} />
        ) : (
          <Button
            label="Place pickup order"
            loading={submitting}
            disabled={!settings.data || !settings.data.pickupOrdersEnabled || user?.pickupDisabled}
            onPress={() => void placeOrder()}
          />
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
  heading: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
    marginBottom: space.sm,
  },
  line: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: space.md,
    marginBottom: space.sm,
  },
  copy: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 15,
    flexShrink: 1,
  },
  totalLabel: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 18,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    lineHeight: 22,
    marginTop: space.sm,
  },
  label: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
    marginTop: space.lg,
  },
  input: {
    minHeight: 96,
    borderWidth: 1,
    borderColor: colors.goldBorder,
    borderRadius: radius.md,
    padding: space.md,
    color: colors.text,
    fontFamily: type.body,
    fontSize: 16,
    textAlignVertical: 'top',
    marginBottom: space.lg,
  },
  error: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    lineHeight: 22,
    marginVertical: space.sm,
  },
});
