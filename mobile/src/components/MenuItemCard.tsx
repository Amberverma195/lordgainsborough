import { Pressable, StyleSheet, Text, View } from 'react-native';

import { MenuItem } from '../api/parse';
import { colors, radius, space, type } from '../theme/tokens';
import { Card } from './Card';
import { DishImage } from './DishImage';
import { Price } from './Price';

export function MenuItemCard({
  item,
  onPress,
  favourite,
  onToggleFavourite,
}: {
  item: MenuItem;
  onPress: () => void;
  favourite?: boolean;
  onToggleFavourite?: () => void;
}) {
  return (
    <View>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${item.name}, ${item.available ? 'available' : 'unavailable'}`}
        onPress={onPress}
      >
        <Card style={styles.card}>
          <DishImage image={item.image} title={item.name} height={168} />
          <View style={styles.body}>
            <View style={styles.titleRow}>
              <Text style={styles.name}>{item.name}</Text>
              {item.mostLoved ? (
                <Text style={styles.loved}>Most loved</Text>
              ) : null}
            </View>
            {item.description ? (
              <Text style={styles.description} numberOfLines={2}>
                {item.description}
              </Text>
            ) : null}
            <View style={styles.footer}>
              <Price amount={item.price} />
              <Text
                style={item.available ? styles.available : styles.unavailable}
              >
                {item.available ? 'Available' : 'Unavailable'}
              </Text>
            </View>
          </View>
        </Card>
      </Pressable>
      {onToggleFavourite ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={
            (favourite ? 'Remove favourite ' : 'Add favourite ') + item.name
          }
          accessibilityState={{ selected: !!favourite }}
          onPress={(event) => {
            event.stopPropagation();
            onToggleFavourite();
          }}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ color: colors.gold }}>
            {favourite ? '\u2665 Saved' : '\u2661 Favourite'}
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: 0,
    marginBottom: space.lg,
  },
  body: {
    padding: space.lg,
    gap: space.sm,
  },
  titleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: space.sm,
    alignItems: 'flex-start',
  },
  name: {
    flex: 1,
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
  },
  loved: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 11,
    letterSpacing: 0.6,
    textTransform: 'uppercase',
    borderWidth: 1,
    borderColor: colors.goldBorder,
    borderRadius: radius.pill,
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  description: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 15,
    lineHeight: 22,
  },
  footer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: space.sm,
  },
  available: {
    color: colors.success,
    fontFamily: type.label,
    fontSize: 13,
  },
  unavailable: {
    color: colors.danger,
    fontFamily: type.label,
    fontSize: 13,
  },
});
