import { useState } from 'react';
import { Image, ImageStyle } from 'expo-image';
import { StyleProp, StyleSheet, Text, View } from 'react-native';

import { resolveMediaUrl } from '../api/config';
import { resolveApiOrigins } from '../api/runtime';
import { colors, type } from '../theme/tokens';

export function DishImage({
  image,
  title,
  height = 180,
  width = '100%',
  style,
}: {
  image?: string | null;
  title: string;
  height?: number;
  width?: number | `${number}%` | 'auto';
  style?: StyleProp<ImageStyle>;
}) {
  const uri = resolveMediaUrl(image, resolveApiOrigins());
  const [failedUri, setFailedUri] = useState<string | null>(null);
  if (!uri || failedUri === uri || (!__DEV__ && uri.startsWith('http:'))) {
    return (
      <View style={[styles.fallback, { height, width: width as any }, style as any]}>
        <Text style={styles.monogram}>LG</Text>
        <Text style={styles.caption} numberOfLines={1}>
          {title}
        </Text>
      </View>
    );
  }
  return (
    <Image
      source={{ uri }}
      onError={() => setFailedUri(uri)}
      style={[{ height, width: width as any }, style]}
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
