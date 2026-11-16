import { useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import { fetchOrder } from '../../api/orders';
import { ApiError } from '../../api/errors';
import { CustomerOrderView } from '../../api/parse';
import { useAuth } from '../../auth/AuthContext';
import { readTrackingToken } from '../../auth/secureSession';
import { readConfirmedOrder } from '../../checkout/confirmationCache';
import { formatPickupDateLabel, formatPickupTimeLabel } from '../../checkout/pickup';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { Price } from '../../components/Price';
import { Screen } from '../../components/Screen';
import { ErrorState, LoadingState } from '../../components/States';
import { StatusBadge } from '../../components/StatusBadge';
import { formatMoney } from '../../cart/money';
import { formatOrderReference } from '../../format/display';
import { isActiveOrderStatus } from '../../order/status';
import { useOrderRealtime } from '../../order/useOrderRealtime';
import { colors, space, type } from '../../theme/tokens';

export default function OrderDetailScreen() {
  const params = useLocalSearchParams<{ id: string; confirmed?: string }>();
  const orderId = Array.isArray(params.id) ? params.id[0] : params.id;
  const confirmed = params.confirmed === '1';
  const { user, status } = useAuth();
  const queryClient = useQueryClient();
  const [trackingToken, setTrackingToken] = useState<string | null>(null);
  const cached = orderId ? readConfirmedOrder(orderId, user?.email) : null;

  useEffect(() => {
    let cancelled = false;

    if (!orderId || status !== 'signedIn' || !user?.email) {
      return () => {
        cancelled = true;
      };
    }

    void readTrackingToken(orderId, user.email).then((token) => {
      if (!cancelled) {
        setTrackingToken(token);
      }
    });

    return () => {
      cancelled = true;
      setTrackingToken(null);
    };
  }, [orderId, status, user?.email]);

  const order = useQuery({
    queryKey: ['order', orderId, user?.email],
    queryFn: () => fetchOrder(orderId, user!.token, trackingToken),
    enabled: status === 'signedIn' && !!user && !!orderId,
    refetchInterval: (query) => {
      const orderStatus = query.state.data?.status ?? cached?.status;
      return isActiveOrderStatus(orderStatus) ? 5_000 : false;
    },
    refetchIntervalInBackground: false,
  });

  const display = order.data ?? cached;
  const refetchRef = useRef(order.refetch);
  useEffect(() => {
    refetchRef.current = order.refetch;
  });

  useFocusEffect(
    useCallback(() => {
      if (status === 'signedIn' && user && orderId) {
        void refetchRef.current();
      }
    }, [orderId, status, user]),
  );

  const handleConnect = useCallback(() => {
    void refetchRef.current();
  }, []);

  const handleOrderUpdated = useCallback(
    (updatedOrder: CustomerOrderView) => {
      queryClient.setQueryData(['order', orderId, user?.email], updatedOrder);
      void queryClient.invalidateQueries({ queryKey: ['orders', user?.email] });
    },
    [orderId, queryClient, user?.email],
  );

  useOrderRealtime({
    orderId,
    token: user?.token,
    trackingToken,
    enabled: status === 'signedIn' && !!orderId,
    onConnect: handleConnect,
    onOrderUpdated: handleOrderUpdated,
  });

  return (
    <Screen
      scroll
      refreshControl={
        <RefreshControl
          refreshing={order.isRefetching}
          onRefresh={() => void order.refetch()}
          tintColor={colors.gold}
          colors={[colors.gold]}
        />
      }
    >
      <AppHeader title={confirmed ? 'Confirmed' : 'Order'} showBack />
      {order.isLoading && !display ? <LoadingState label="Loading your order" /> : null}
      {!display && !order.isLoading && !order.isError ? (
        <Text style={styles.note}>Sign in with the account that placed this order to view it.</Text>
      ) : null}
      {!display && order.isError ? (
        <ErrorState
          title="Order unavailable"
          message={order.error instanceof ApiError ? order.error.message : 'This order could not be loaded.'}
          onRetry={() => void order.refetch()}
        />
      ) : null}
      {display ? (
        <View style={styles.stack}>
          {confirmed ? <Text style={styles.kicker}>Sent to the kitchen</Text> : null}
          <Text style={styles.reference}>{formatOrderReference(display.id)}</Text>
          <StatusBadge status={display.status} />
          <Card>
            <Text style={styles.label}>Pickup</Text>
            <Text style={styles.value}>{formatPickupDateLabel(display.pickupDate)}</Text>
            <Text style={styles.value}>{formatPickupTimeLabel(display.pickupTime)}</Text>
            {display.specialInstructions ? (
              <>
                <Text style={styles.label}>Instructions</Text>
                <Text style={styles.value}>{display.specialInstructions}</Text>
              </>
            ) : null}
          </Card>
          <Card>
            <Text style={styles.label}>Summary</Text>
            {display.items.map((item, index) => (
              <Text key={`${item.menuItemId}-${index}`} style={styles.value}>
                {item.quantity} × {item.name} · {formatMoney(item.total)}
              </Text>
            ))}
            <View style={styles.totalRow}>
              <Text style={styles.total}>Total</Text>
              <Price amount={display.total} />
            </View>
            <Text style={styles.note}>Tax {formatMoney(display.tax)} is included in the restaurant total. Pay when you pick up.</Text>
          </Card>
          <Button label="View all orders" onPress={() => router.push('/(tabs)/orders')} />
        </View>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  stack: {
    gap: space.lg,
  },
  kicker: {
    color: colors.gold,
    fontFamily: type.label,
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  reference: {
    color: colors.text,
    fontFamily: type.display,
    fontSize: 36,
  },
  label: {
    color: colors.gold,
    fontFamily: type.label,
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: space.sm,
  },
  value: {
    color: colors.text,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
  },
  totalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: space.md,
  },
  total: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    lineHeight: 22,
    marginTop: space.sm,
  },
});
