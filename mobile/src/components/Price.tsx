import { StyleSheet, Text } from 'react-native';

import { formatMoney } from '../cart/money';
import { colors, type } from '../theme/tokens';

export function Price({ amount, muted = false, large = false }: { amount: number; muted?: boolean; large?: boolean }) {
  return (
    <Text accessibilityLabel={formatMoney(amount)} style={[styles.price, large && styles.large, muted && styles.muted]}>
      {formatMoney(amount)}
    </Text>
  );
}

const styles = StyleSheet.create({
  price: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 16,
  },
  large: {
    fontFamily: type.heading,
    fontSize: 28,
  },
  muted: {
    color: colors.textSecondary,
  },
});
