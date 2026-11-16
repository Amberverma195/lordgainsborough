import { StyleSheet, Text, View } from 'react-native';

import { orderStatusLabel } from '../format/display';
import { colors, radius, type } from '../theme/tokens';

export function StatusBadge({ status }: { status: string }) {
  return (
    <View style={styles.badge}>
      <Text style={styles.text}>{orderStatusLabel(status)}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    alignSelf: 'flex-start',
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.goldBorder,
    backgroundColor: colors.goldSoft,
    paddingHorizontal: 10,
    paddingVertical: 4,
  },
  text: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 0.4,
  },
});
