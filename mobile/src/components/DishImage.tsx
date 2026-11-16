import { Image } from 'expo-image';
import { StyleSheet, Text, View } from 'react-native';

import { resolveMediaUrl } from '../api/config';
import { resolveApiOrigins } from '../api/runtime';
import { colors, type } from '../theme/tokens';

export function DishImage({
  image,
  title,
  height = 180,
}: {
  image?: string | null;
  title: string;
  height?: number;
}) {
  const uri = resolveMediaUrl(image, resolveApiOrigins());
  if (!uri) {
    return (
      <View style={[styles.fallback, { height }]}>
        <Text style={styles.monogram}>LG</Text>
        <Text style={styles.caption} numberOfLines={1}>{title}</Text>
      </View>
    );
  }
  return (
    <Image
      source={{ uri }}
      style={{ height, width: '100%' }}
      contentFit="cover"
      accessibilityLabel={title}
      transition={180}
    />
  );
}

const styles = StyleSheet.create({
  fallback: {
    backgroundColor: colors.backgroundRaised,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
  },
  monogram: {
    color: colors.gold,
    fontFamily: type.display,
    fontSize: 28,
    letterSpacing: 3,
  },
  caption: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 12,
    paddingHorizontal: 12,
  },
});
