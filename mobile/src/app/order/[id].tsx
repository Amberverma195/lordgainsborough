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

  const currentAccount = status === 'signedIn' && user?.email ? user.email.trim().toLowerCase() : null;
  const authToken = status === 'signedIn' && user?.token ? user.token : null;

  // Stored tracking credential bound explicitly to the account identity and order ID
  const [boundTracking, setBoundTracking] = useState<{
    accountEmail: string;
    orderId: string;
    token: string | null;
  } | null>(null);

  // Synchronously evaluated on every render:
  // If the bound credential's account or order ID does not match the currently active
  // account, it evaluates to null synchronously on that exact render. This prevents
  // previous-account credentials from ever being used during intermediate renders
  // before effect cleanups or state transitions settle.
  const isCredentialMatch =
    boundTracking !== null &&
    boundTracking.orderId === orderId &&
    boundTracking.accountEmail === currentAccount;

  const effectiveTrackingToken = isCredentialMatch ? boundTracking.token : null;

  // Prefer JWT-only authorization for authenticated account-owned order requests:
  // When an authenticated JWT is available, do not pass tracking credentials to endpoints
  // or Socket.IO; the backend validates account ownership directly via the JWT.
  const requestTrackingToken = authToken ? null : effectiveTrackingToken;

  const cached = orderId ? readConfirmedOrder(orderId, user?.email) : null;

  useEffect(() => {
    let cancelled = false;

    if (!orderId || !currentAccount) {
      return () => {
        cancelled = true;
      };
    }

    void readTrackingToken(orderId, currentAccount).then((token) => {
      if (!cancelled) {
        setBoundTracking({
          accountEmail: currentAccount,
          orderId,
          token,
        });
      }
    });

    return () => {
      cancelled = true;
      setBoundTracking(null);
    };
  }, [orderId, currentAccount]);

  const order = useQuery({
    queryKey: ['order', orderId, currentAccount],
    queryFn: () => fetchOrder(orderId, authToken!, requestTrackingToken),
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
    token: authToken,
    trackingToken: requestTrackingToken,
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
