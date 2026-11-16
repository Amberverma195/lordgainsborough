import { router } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { EmptyState } from '../../components/States';
import { PickupPicker } from '../../components/PickupPicker';
import { Price } from '../../components/Price';
import { QuantityStepper } from '../../components/QuantityStepper';
import { Screen } from '../../components/Screen';
import { useCart } from '../../cart/CartContext';
import { colors, space, type } from '../../theme/tokens';

export default function CartScreen() {
  const { lines, count, notice, pickupDate, pickupTime, updateQuantity, removeItem, setPickup, estimate } = useCart();
  const totals = estimate(0);

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
          <Card key={line.menuItemId}>
            <Text style={styles.name}>{line.name}</Text>
            {!line.available ? <Text style={styles.unavailable}>Currently unavailable</Text> : null}
            <View style={styles.row}>
              <Price amount={line.unitPrice} />
              <QuantityStepper
                quantity={line.quantity}
                label={line.name}
                onChange={(quantity) => updateQuantity(line.menuItemId, quantity)}
              />
            </View>
            <Button label="Remove" variant="ghost" onPress={() => removeItem(line.menuItemId)} />
          </Card>
        ))}
      </View>
      <PickupPicker date={pickupDate} time={pickupTime} onChange={setPickup} />
      <View style={styles.summary}>
        <Text style={styles.summaryLabel}>Subtotal</Text>
        <Price amount={totals.subtotal} />
      </View>
      <Text style={styles.help}>Tax is calculated from the restaurant’s current rate at checkout. The kitchen confirms the final total.</Text>
      <Button label={`Continue · ${count} ${count === 1 ? 'item' : 'items'}`} onPress={() => router.push('/checkout')} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: {
    gap: space.md,
    marginBottom: space.xl,
  },
  name: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
    marginBottom: space.sm,
  },
  unavailable: {
    color: colors.danger,
    fontFamily: type.label,
    marginBottom: space.sm,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: space.md,
  },
  summary: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: space.xl,
    marginBottom: space.sm,
  },
  summaryLabel: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
  },
  help: {
    color: colors.textSecondary,
    fontFamily: type.body,
    lineHeight: 22,
    marginBottom: space.lg,
  },
  notice: {
    color: colors.danger,
    fontFamily: type.bodyMedium,
    marginBottom: space.md,
  },
});
