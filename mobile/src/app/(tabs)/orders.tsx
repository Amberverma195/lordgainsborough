import { useQuery } from '@tanstack/react-query';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef } from 'react';
import {
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { fetchOrderHistory } from '../../api/orders';
import { ApiError } from '../../api/errors';
import { useAuth } from '../../auth/AuthContext';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { Price } from '../../components/Price';
import { Screen } from '../../components/Screen';
import { ErrorState, LoadingState } from '../../components/States';
import { StatusBadge } from '../../components/StatusBadge';
import {
  formatPickupDateLabel,
  formatPickupTimeLabel,
} from '../../checkout/pickup';
import { formatOrderReference } from '../../format/display';
import { isActiveOrderStatus } from '../../order/status';
import { colors, space, type } from '../../theme/tokens';

export default function OrdersScreen() {
  const { status, user, refresh } = useAuth();
  const currentAccount = status === 'signedIn' && user?.email ? user.email.trim().toLowerCase() : null;
  const orders = useQuery({
    queryKey: ['orders', currentAccount],
    queryFn: () => fetchOrderHistory(user!.token),
    enabled: status === 'signedIn' && !!user,
    refetchInterval: (query) => {
      const list = query.state.data;
      const hasActive =
        list?.some((o) => isActiveOrderStatus(o.status)) ?? false;
      return hasActive ? 10_000 : 30_000;
    },
    refetchIntervalInBackground: false,
  });

  const refetchRef = useRef(orders.refetch);
  useEffect(() => {
    refetchRef.current = orders.refetch;
  });

  useFocusEffect(
    useCallback(() => {
      if (status === 'signedIn' && user) {
        void refetchRef.current();
      }
    }, [status, user]),
  );

  return (
    <Screen
      scroll
      refreshControl={
        <RefreshControl
          refreshing={orders.isRefetching}
          onRefresh={() => void orders.refetch()}
          tintColor={colors.gold}
          colors={[colors.gold]}
        />
      }
    >
      <AppHeader title="Orders" eyebrow="Pickup" />
      {status === 'loading' ? (
        <LoadingState label="Restoring your session" />
      ) : null}
      {status === 'unreachable' ? (
        <ErrorState
          title="Session unavailable"
          message="We could not confirm your sign-in. Your saved session was kept."
          onRetry={() => void refresh()}
        />
      ) : null}
      {status === 'signedOut' ? (
        <View style={styles.gap}>
          <Text style={styles.copy}>
            Sign in to see pickup orders placed with your account.
          </Text>
          <Button label="Sign in" onPress={() => router.push('/auth/login')} />
        </View>
      ) : null}
      {orders.isLoading ? <LoadingState label="Loading your orders" /> : null}
      {orders.isError ? (
        <ErrorState
          title="Orders unavailable"
          message={
            orders.error instanceof ApiError
              ? orders.error.message
              : 'Your orders could not be loaded.'
          }
          onRetry={() => void orders.refetch()}
        />
      ) : null}
      {orders.data && orders.data.length === 0 ? (
        <Text style={styles.copy}>You have no pickup orders yet.</Text>
      ) : null}
      <View style={styles.list}>
        {orders.data?.map((order) => (
          <View key={order.id}>
            <Pressable
              accessibilityRole="button"
              onPress={() => router.push(`/order/${order.id}`)}
            >
              <Card>
                <Text style={styles.reference}>
                  {formatOrderReference(order.id)}
                </Text>
                <StatusBadge status={order.status} />
                <Text style={styles.copy}>
                  {formatPickupDateLabel(order.pickupDate)} ·{' '}
                  {formatPickupTimeLabel(order.pickupTime)}
                </Text>
                <Price amount={order.total} />
              </Card>
            </Pressable>
            {order.status === 'PICKED_UP' ? (
              <Button
                label="Reorder"
                variant="secondary"
                onPress={() => router.push('/reorder/' + order.id)}
              />
            ) : null}
          </View>
        ))}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: {
    gap: space.md,
  },
  list: {
    gap: space.md,
    marginTop: space.md,
  },
  reference: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
    marginBottom: space.sm,
  },
  copy: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
    marginVertical: space.sm,
  },
});
