import { Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, radius, type } from '../theme/tokens';

export function QuantityStepper({
  quantity,
  onChange,
  label = 'Quantity',
}: {
  quantity: number;
  onChange: (quantity: number) => void;
  label?: string;
}) {
  return (
    <View accessibilityLabel={label} style={styles.row}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Decrease ${label}`}
        onPress={() => onChange(quantity - 1)}
        style={styles.button}
      >
        <Text style={styles.symbol}>−</Text>
      </Pressable>
      <Text accessibilityLabel={`${quantity} selected`} style={styles.value}>{quantity}</Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Increase ${label}`}
        onPress={() => onChange(quantity + 1)}
        style={styles.button}
      >
        <Text style={styles.symbol}>+</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.goldBorder,
    overflow: 'hidden',
  },
  button: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  symbol: {
    color: colors.gold,
    fontSize: 22,
    fontFamily: type.heading,
  },
  value: {
    minWidth: 28,
    textAlign: 'center',
    color: colors.text,
    fontFamily: type.label,
    fontSize: 16,
  },
});
