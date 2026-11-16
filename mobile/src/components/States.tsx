import { StyleSheet, Text, View } from 'react-native';

import { colors, space, type } from '../theme/tokens';
import { Button } from './Button';

export function EmptyState({
  title,
  message,
  actionLabel,
  onAction,
}: {
  title: string;
  message: string;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <View style={styles.wrap}>
      <Text style={styles.kicker}>Lord Gainsborough</Text>
      <Text style={styles.title}>{title}</Text>
      <Text style={styles.message}>{message}</Text>
      {actionLabel && onAction ? <Button label={actionLabel} onPress={onAction} /> : null}
    </View>
  );
}

export function ErrorState({
  title = 'Something needs attention',
  message,
  onRetry,
}: {
  title?: string;
  message: string;
  onRetry?: () => void;
}) {
  return (
    <View style={styles.wrap}>
      <Text style={styles.title}>{title}</Text>
      <Text style={styles.message}>{message}</Text>
      {onRetry ? <Button label="Try again" onPress={onRetry} variant="secondary" /> : null}
    </View>
  );
}

export function LoadingState({ label }: { label: string }) {
  return (
    <View style={styles.wrap} accessibilityRole="progressbar" accessibilityLabel={label}>
      <View style={styles.rule} />
      <Text style={styles.title}>{label}</Text>
      <Text style={styles.message}>Please wait a moment.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    alignItems: 'flex-start',
    gap: space.md,
    paddingVertical: space.xxl,
  },
  kicker: {
    color: colors.gold,
    fontFamily: type.label,
    letterSpacing: 2,
    textTransform: 'uppercase',
    fontSize: 12,
  },
  title: {
    color: colors.text,
    fontFamily: type.display,
    fontSize: 32,
    lineHeight: 38,
  },
  message: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
  },
  rule: {
    width: 48,
    height: 1,
    backgroundColor: colors.gold,
  },
});
