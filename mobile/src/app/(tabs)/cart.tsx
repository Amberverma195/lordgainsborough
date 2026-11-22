import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { fetchStoreSettings } from '../../api/catalog';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { DishImage } from '../../components/DishImage';
import { EmptyState } from '../../components/States';
import { PickupPicker } from '../../components/PickupPicker';
import { Price } from '../../components/Price';
import { QuantityStepper } from '../../components/QuantityStepper';
import { Screen } from '../../components/Screen';
import { useCart } from '../../cart/CartContext';
import { formatMoney } from '../../cart/money';
import { useCartTax } from '../../cart/tax';
import { colors, radius, space, type } from '../../theme/tokens';

export default function CartScreen() {
  const { lines, count, notice, pickupDate, pickupTime, updateQuantity, removeItem, setPickup, estimate } = useCart();
  const settings = useQuery({
    queryKey: ['settings'],
    queryFn: ({ signal }) => fetchStoreSettings(signal),
  });
  const tax = useCartTax(settings);
  const totals = estimate(tax.taxRate);

  if (lines.length === 0) {
    return (
      <Screen>
        <AppHeader title="Cart" eyebrow="Pickup" showCart={false} />
        <EmptyState
          title="Your cart is empty"
          message="Choose a dish from the menu when you are ready to order for pickup."
          actionLabel="Explore Menu"
          onAction={() => router.push('/(tabs)/menu')}
        />
      </Screen>
    );
  }

  return (
    <Screen scroll>
      <AppHeader title="Cart" eyebrow="Pickup" showCart={false} />
      {notice ? <Text style={styles.notice}>{notice}</Text> : null}
      <View style={styles.list}>
        {lines.map((line) => (
          <Card key={line.menuItemId} style={styles.itemCard}>
            <View style={styles.itemHeader}>
              <View style={styles.imageWrapper}>
                <DishImage image={line.image} title={line.name} height={64} width={64} style={styles.dishImage} />
              </View>
              <View style={styles.itemMeta}>
                <Text style={styles.name} numberOfLines={2}>{line.name}</Text>
                <View style={styles.priceRow}>
                  <Text style={styles.unitPrice}>{formatMoney(line.unitPrice)} each</Text>
                  <Text style={styles.lineTotal}>{formatMoney(line.unitPrice * line.quantity)}</Text>
                </View>
              </View>
            </View>
            {!line.available ? <Text style={styles.unavailable}>Currently unavailable</Text> : null}
            <View style={styles.controlsRow}>
              <QuantityStepper
                quantity={line.quantity}
                label={line.name}
                onChange={(quantity) => updateQuantity(line.menuItemId, quantity)}
              />
              <Button label="Remove" variant="ghost" onPress={() => removeItem(line.menuItemId)} />
            </View>
          </Card>
        ))}
      </View>

      <PickupPicker date={pickupDate} time={pickupTime} onChange={setPickup} />

      <Card style={styles.summaryCard}>
        <Text style={styles.summaryTitle}>Order Summary</Text>
        <View style={styles.summaryRow}>
          <Text style={styles.summaryLabel}>Subtotal</Text>
          <Text style={styles.summaryValue}>{formatMoney(totals.subtotal)}</Text>
        </View>
        <View style={styles.summaryRow}>
          <Text style={styles.summaryLabel}>
            {tax.taxLabel}
          </Text>
          <Text style={styles.summaryValue}>
            {tax.isCalculating ? 'Calculating tax...' : formatMoney(totals.tax)}
          </Text>
        </View>
        <View style={styles.divider} />
        <View style={styles.totalRow}>
          <Text style={styles.totalLabel}>Estimated Total</Text>
          <Price amount={totals.total} large />
        </View>
        {tax.notice ? (
          <Text style={styles.taxNotice}>{tax.notice}</Text>
        ) : null}
        <Text style={styles.help}>
          Payment is made in person when picking up your order. The kitchen confirms the final total.
        </Text>
      </Card>

      <Button
        label={`Continue to Checkout · ${count} ${count === 1 ? 'item' : 'items'}`}
        onPress={() => router.push('/checkout')}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: {
    gap: space.md,
    marginBottom: space.lg,
  },
  itemCard: {
    gap: space.sm,
  },
  itemHeader: {
    flexDirection: 'row',
    gap: space.md,
    alignItems: 'center',
  },
  imageWrapper: {
    width: 64,
    height: 64,
    borderRadius: radius.md,
    overflow: 'hidden',
    backgroundColor: colors.backgroundRaised,
  },
  dishImage: {
    borderRadius: radius.md,
  },
  itemMeta: {
    flex: 1,
    gap: 4,
  },
  name: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 18,
  },
  priceRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  unitPrice: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 14,
  },
  lineTotal: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 15,
  },
  unavailable: {
    color: colors.danger,
    fontFamily: type.label,
    fontSize: 12,
  },
  controlsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: space.xs,
  },
  summaryCard: {
    gap: space.sm,
    marginTop: space.lg,
    marginBottom: space.lg,
  },
  summaryTitle: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1.5,
    textTransform: 'uppercase',
    marginBottom: space.xs,
  },
  summaryRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  summaryLabel: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 15,
  },
  summaryValue: {
    color: colors.text,
    fontFamily: type.bodyMedium,
    fontSize: 15,
  },
  divider: {
    height: 1,
    backgroundColor: colors.goldBorder,
    marginVertical: space.xs,
  },
  totalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: space.xs,
  },
  totalLabel: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
  },
  help: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 13,
    lineHeight: 18,
    marginTop: space.xs,
  },
  taxNotice: {
    color: colors.gold,
    fontFamily: type.body,
    fontSize: 12,
    lineHeight: 16,
    marginTop: space.xs,
  },
  notice: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    marginBottom: space.md,
  },
});
