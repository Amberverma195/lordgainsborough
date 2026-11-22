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
import { OrderProgressTracker } from '../../components/OrderProgressTracker';
import { Price } from '../../components/Price';
import { Screen } from '../../components/Screen';
import { ErrorState, LoadingState } from '../../components/States';
import { StatusBadge } from '../../components/StatusBadge';
import { formatMoney } from '../../cart/money';
import { formatOrderId } from '../../format/display';
import { isActiveOrderStatus } from '../../order/status';
import { useOrderRealtime } from '../../order/useOrderRealtime';
import { colors, radius, space, type } from '../../theme/tokens';

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

  const [isSocketConnected, setIsSocketConnected] = useState(false);

  const handleConnect = useCallback(() => {
    setIsSocketConnected(true);
    void refetchRef.current();
  }, []);

  const handleDisconnect = useCallback(() => {
    setIsSocketConnected(false);
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
    onDisconnect: handleDisconnect,
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
      <AppHeader title={confirmed ? 'Confirmed' : 'Order Details'} showBack />
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

          {/* Row 1: Very first row: Order ID, then the date and time, and then the status */}
          <Card style={styles.heroCard}>
            <View style={styles.heroRow}>
              <View style={styles.heroLeft}>
                <Text style={styles.orderId}>#{formatOrderId(display.id)}</Text>
                <Text style={styles.dateTimeText}>
                  {formatPickupDateLabel(display.pickupDate)} · {formatPickupTimeLabel(display.pickupTime)}
                </Text>
              </View>
              <View style={styles.heroRight}>
                <StatusBadge status={display.status} />
                {isActiveOrderStatus(display.status) ? (
                  <View style={styles.liveBadge}>
                    <View
                      style={[
                        styles.liveDot,
                        isSocketConnected ? styles.liveDotGreen : styles.liveDotAmber,
                      ]}
                    />
                    <Text style={styles.liveText}>
                      {isSocketConnected ? 'Live' : 'Polling'}
                    </Text>
                  </View>
                ) : null}
              </View>
            </View>
          </Card>

          {/* Row 2: Order summary (below first row) */}
          <Card style={styles.summaryCard}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardHeaderTitle}>Order Summary</Text>
              <View style={styles.itemCountBadge}>
                <Text style={styles.itemCountText}>
                  {display.items.reduce((acc, it) => acc + it.quantity, 0)}{' '}
                  {display.items.reduce((acc, it) => acc + it.quantity, 0) === 1 ? 'item' : 'items'}
                </Text>
              </View>
            </View>

            <View style={styles.itemsList}>
              {display.items.map((item, index) => (
                <View key={`${item.menuItemId}-${index}`} style={styles.itemRow}>
                  <View style={styles.itemMain}>
                    <Text style={styles.itemQty}>{item.quantity}×</Text>
                    <View style={styles.itemMeta}>
                      <Text style={styles.itemName}>{item.name}</Text>
                      <Text style={styles.itemUnitPrice}>{formatMoney(item.unitPrice)} each</Text>
                    </View>
                  </View>
                  <Text style={styles.itemTotal}>{formatMoney(item.total)}</Text>
                </View>
              ))}
            </View>

            <View style={styles.divider} />

            <View style={styles.totalsSection}>
              <View style={styles.totalsRow}>
                <Text style={styles.totalsLabel}>Subtotal</Text>
                <Text style={styles.totalsValue}>{formatMoney(display.subtotal)}</Text>
              </View>
              <View style={styles.totalsRow}>
                <Text style={styles.totalsLabel}>Tax</Text>
                <Text style={styles.totalsValue}>{formatMoney(display.tax)}</Text>
              </View>
              <View style={styles.grandTotalRow}>
                <Text style={styles.grandTotalLabel}>Total Paid</Text>
                <Price amount={display.total} large />
              </View>
            </View>
          </Card>

          {/* Row 3: Special instructions (below order summary) */}
          {display.specialInstructions?.trim() ? (
            <Card style={styles.specialCard}>
              <Text style={styles.sectionTitle}>Special Instructions</Text>
              <Text style={styles.instructionsText}>{display.specialInstructions.trim()}</Text>
            </Card>
          ) : null}

          {/* Row 4: Order progress (below special instructions) */}
          <OrderProgressTracker
            status={display.status}
            createdAt={display.createdAt}
            acceptedAt={display.acceptedAt}
            readyAt={display.readyAt}
            pickedUpAt={display.pickedUpAt}
            noShowAt={display.noShowAt}
          />

          {/* Dialog box / pickup info: pushed all the way at the bottom */}
          <Card style={styles.pickupInfoCard}>
            <Text style={styles.pickupInfoTitle}>Pay at Restaurant on Pickup</Text>
            <Text style={styles.pickupInfoBody}>
              All orders are prepared for in-person pickup. Payment is collected at the restaurant counter.
            </Text>
            <Text style={styles.pickupContact}>Restaurant: (905) 892-0700</Text>
          </Card>

          {/* Action buttons */}
          <View style={styles.actionButtons}>
            {display.status === 'PICKED_UP' ? (
              <Button
                label="Reorder this meal"
                variant="primary"
                onPress={() => router.push('/reorder/' + display.id)}
              />
            ) : null}
            <Button
              label="View all orders"
              variant={display.status === 'PICKED_UP' ? 'secondary' : 'primary'}
              onPress={() => router.push('/(tabs)/orders')}
            />
          </View>
        </View>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  stack: {
    gap: space.lg,
    paddingBottom: space.xl,
  },
  kicker: {
    color: colors.gold,
    fontFamily: type.label,
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  heroCard: {
    padding: space.md,
  },
  heroRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  heroLeft: {
    gap: 4,
  },
  orderId: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 26,
    letterSpacing: 0.5,
  },
  dateTimeText: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 14,
  },
  heroRight: {
    alignItems: 'flex-end',
    gap: 6,
  },
  liveBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
  },
  liveDot: {
    width: 7,
    height: 7,
    borderRadius: 3.5,
  },
  liveDotGreen: {
    backgroundColor: '#22C55E',
  },
  liveDotAmber: {
    backgroundColor: colors.gold,
  },
  liveText: {
    color: colors.textMuted,
    fontFamily: type.label,
    fontSize: 10,
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  summaryCard: {
    gap: space.md,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  cardHeaderTitle: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
  },
  itemCountBadge: {
    backgroundColor: colors.goldSoft,
    borderColor: colors.goldBorder,
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: 10,
    paddingVertical: 3,
  },
  itemCountText: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
  },
  itemsList: {
    gap: space.sm,
  },
  itemRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 4,
  },
  itemMain: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    flex: 1,
  },
  itemQty: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 15,
    minWidth: 26,
  },
  itemMeta: {
    flex: 1,
  },
  itemName: {
    color: colors.text,
    fontFamily: type.bodyMedium,
    fontSize: 15,
  },
  itemUnitPrice: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 13,
  },
  itemTotal: {
    color: colors.text,
    fontFamily: type.label,
    fontSize: 15,
  },
  divider: {
    height: 1,
    backgroundColor: colors.goldBorder,
    marginVertical: space.xs,
  },
  totalsSection: {
    gap: space.xs,
  },
  totalsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  totalsLabel: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 15,
  },
  totalsValue: {
    color: colors.text,
    fontFamily: type.bodyMedium,
    fontSize: 15,
  },
  grandTotalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: space.sm,
  },
  grandTotalLabel: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 18,
  },
  specialCard: {
    gap: space.xs,
  },
  sectionTitle: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  instructionsText: {
    color: colors.text,
    fontFamily: type.body,
    fontSize: 15,
    lineHeight: 22,
  },
  pickupInfoCard: {
    gap: 6,
    backgroundColor: colors.backgroundRaised,
  },
  pickupInfoTitle: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  pickupInfoBody: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 14,
    lineHeight: 20,
  },
  pickupContact: {
    color: colors.text,
    fontFamily: type.bodyMedium,
    fontSize: 14,
    marginTop: 2,
  },
  actionButtons: {
    gap: space.md,
    marginTop: space.xs,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    lineHeight: 22,
  },
});
