import Ionicons from '@expo/vector-icons/Ionicons';
import { router } from 'expo-router';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, space, type } from '../theme/tokens';

export function AppHeader({
  title,
  eyebrow,
  cartCount = 0,
  showBack = false,
  showCart = true,
}: {
  title: string;
  eyebrow?: string;
  cartCount?: number;
  showBack?: boolean;
  showCart?: boolean;
}) {
  return (
    <View style={styles.row}>
      {showBack ? (
        <Pressable accessibilityRole="button" accessibilityLabel="Go back" onPress={() => router.back()} style={styles.iconButton}>
          <Ionicons name="chevron-back" size={22} color={colors.gold} />
        </Pressable>
      ) : (
        <View style={styles.iconButton} />
      )}
      <View style={styles.center}>
        {eyebrow ? <Text style={styles.eyebrow}>{eyebrow}</Text> : null}
        <Text style={styles.title} numberOfLines={1}>{title}</Text>
      </View>
      {showCart ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={cartCount > 0 ? `Cart, ${cartCount} items` : 'Cart'}
          onPress={() => router.push('/(tabs)/cart')}
          style={styles.iconButton}
        >
          <Ionicons name="bag-handle-outline" size={22} color={colors.gold} />
          {cartCount > 0 ? (
            <View style={styles.badge}>
              <Text style={styles.badgeText}>{cartCount > 99 ? '99+' : cartCount}</Text>
            </View>
          ) : null}
        </Pressable>
      ) : (
        <View style={styles.iconButton} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingBottom: space.md,
    gap: space.sm,
  },
  center: {
    flex: 1,
    alignItems: 'center',
  },
  eyebrow: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 11,
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  title: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
  },
  iconButton: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    top: 4,
    right: 2,
    minWidth: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.gold,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 4,
  },
  badgeText: {
    color: colors.background,
    fontFamily: type.label,
    fontSize: 10,
  },
});
