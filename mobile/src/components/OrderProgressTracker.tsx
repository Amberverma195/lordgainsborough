import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { formatProgressTimestamp, orderStatusLabel } from '../format/display';
import { colors, radius, space, type } from '../theme/tokens';
import { Card } from './Card';

export interface OrderProgressTrackerProps {
  status: string;
  createdAt?: string | null;
  acceptedAt?: string | null;
  readyAt?: string | null;
  pickedUpAt?: string | null;
  noShowAt?: string | null;
}

interface StepDef {
  key: string;
  stepNumber: number;
  title: string;
  timestamp?: string | null;
}

export function OrderProgressTracker({
  status,
  createdAt,
  acceptedAt,
  readyAt,
  pickedUpAt,
  noShowAt,
}: OrderProgressTrackerProps) {
  const [historyOpen, setHistoryOpen] = useState(false);

  const isNoShow = status === 'NO_SHOW';
  const currentStepIndex = (() => {
    switch (status) {
      case 'NEW':
        return 0;
      case 'ACCEPTED':
        return 1;
      case 'READY':
        return 2;
      case 'PICKED_UP':
        return 3;
      default:
        return 0;
    }
  })();

  const steps: StepDef[] = [
    { key: 'placed', stepNumber: 1, title: 'Order Placed', timestamp: createdAt },
    { key: 'kitchen', stepNumber: 2, title: 'In Kitchen', timestamp: acceptedAt },
    { key: 'ready', stepNumber: 3, title: 'Ready for Pickup', timestamp: readyAt },
    { key: 'completed', stepNumber: 4, title: 'Picked Up', timestamp: pickedUpAt },
  ];

  const historyEntries = [
    { label: 'Order placed', time: createdAt },
    { label: 'Kitchen accepted', time: acceptedAt },
    { label: 'Ready for pickup', time: readyAt },
    { label: 'Picked up', time: pickedUpAt },
    ...(noShowAt ? [{ label: 'Marked as no-show', time: noShowAt }] : []),
  ].filter((entry) => !!entry.time);

  return (
    <Card style={styles.card}>
      {/* Header */}
      <View style={styles.headerRow}>
        <View style={styles.titleWrap}>
          <Text style={styles.clockIcon}>🕒</Text>
          <Text style={styles.cardHeading}>Order Progress</Text>
        </View>
        <View style={[styles.statusPill, isNoShow && styles.statusPillDanger]}>
          <Text style={[styles.statusPillText, isNoShow && styles.statusPillDangerText]}>
            {isNoShow ? 'No Show' : `Step ${currentStepIndex + 1} of 4`}
          </Text>
        </View>
      </View>

      {/* No Show Alert */}
      {isNoShow ? (
        <View style={styles.noShowAlert}>
          <Text style={styles.noShowAlertTitle}>Order Marked as No-Show</Text>
          <Text style={styles.noShowAlertBody}>
            This pickup was marked as a no-show by the kitchen. Please contact the restaurant at (519) 472-4168 if you have any questions.
          </Text>
        </View>
      ) : null}

      {/* Stepper Row */}
      <View style={styles.stepperContainer}>
        {steps.map((step, index) => {
          const isCompleted = !isNoShow && index < currentStepIndex;
          const isActive = !isNoShow && index === currentStepIndex;
          const isWaiting = isNoShow || index > currentStepIndex;
          const formattedTime = step.timestamp ? formatProgressTimestamp(step.timestamp) : null;

          return (
            <View key={step.key} style={styles.stepColumn}>
              {/* Top row with circle and connector lines */}
              <View style={styles.stepIndicatorRow}>
                <View
                  style={[
                    styles.connector,
                    styles.connectorLeft,
                    index === 0 && styles.connectorHidden,
                    index <= currentStepIndex && !isNoShow && styles.connectorActive,
                  ]}
                />
                <View
                  style={[
                    styles.circle,
                    isCompleted && styles.circleCompleted,
                    isActive && styles.circleActive,
                    isWaiting && styles.circleWaiting,
                  ]}
                >
                  <Text
                    style={[
                      styles.circleNumber,
                      (isCompleted || isActive) && styles.circleNumberActive,
                      isWaiting && styles.circleNumberWaiting,
                    ]}
                  >
                    {isCompleted ? '✓' : step.stepNumber}
                  </Text>
                </View>
                <View
                  style={[
                    styles.connector,
                    styles.connectorRight,
                    index === steps.length - 1 && styles.connectorHidden,
                    index < currentStepIndex && !isNoShow && styles.connectorActive,
                  ]}
                />
              </View>

              {/* Title & Timestamp */}
              <Text
                style={[
                  styles.stepTitle,
                  (isActive || isCompleted) && styles.stepTitleActive,
                  isWaiting && styles.stepTitleWaiting,
                ]}
                numberOfLines={2}
              >
                {step.title}
              </Text>
              <Text
                style={[
                  styles.stepTime,
                  isActive && styles.stepTimeActive,
                  isWaiting && styles.stepTimeWaiting,
                ]}
                numberOfLines={2}
              >
                {formattedTime || 'Waiting'}
              </Text>
            </View>
          );
        })}
      </View>

      {/* Activity History Collapsible */}
      <View style={styles.historySection}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Toggle activity history"
          onPress={() => setHistoryOpen((prev) => !prev)}
          style={styles.historyToggle}
        >
          <View style={styles.historyToggleLeft}>
            <Text style={styles.historyClock}>🕒</Text>
            <Text style={styles.historyToggleText}>Activity History</Text>
          </View>
          <Text style={styles.historyChevron}>{historyOpen ? '▲' : '▼'}</Text>
        </Pressable>

        {historyOpen ? (
          <View style={styles.historyList}>
            {historyEntries.length > 0 ? (
              historyEntries.map((entry, i) => (
                <View key={i} style={styles.historyItem}>
                  <Text style={styles.historyLabel}>{entry.label}</Text>
                  <Text style={styles.historyTime}>{formatProgressTimestamp(entry.time)}</Text>
                </View>
              ))
            ) : (
              <Text style={styles.historyEmpty}>No milestone timestamps recorded yet.</Text>
            )}
          </View>
        ) : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.goldBorder,
    padding: space.lg,
    gap: space.lg,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  titleWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
  },
  clockIcon: {
    fontSize: 18,
    color: colors.gold,
  },
  cardHeading: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 19,
  },
  statusPill: {
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.goldBorder,
    backgroundColor: colors.goldSoft,
  },
  statusPillText: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 0.5,
  },
  statusPillDanger: {
    borderColor: colors.danger,
    backgroundColor: 'rgba(239, 68, 68, 0.12)',
  },
  statusPillDangerText: {
    color: colors.danger,
  },
  noShowAlert: {
    backgroundColor: 'rgba(239, 68, 68, 0.1)',
    borderColor: colors.danger,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: space.md,
    gap: 4,
  },
  noShowAlertTitle: {
    color: colors.danger,
    fontFamily: type.heading,
    fontSize: 15,
  },
  noShowAlertBody: {
    color: colors.text,
    fontFamily: type.body,
    fontSize: 13,
    lineHeight: 18,
  },
  stepperContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    paddingVertical: space.sm,
  },
  stepColumn: {
    flex: 1,
    alignItems: 'center',
  },
  stepIndicatorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    marginBottom: space.sm,
  },
  connector: {
    flex: 1,
    height: 2,
    backgroundColor: '#2A2A2A',
  },
  connectorLeft: {
    marginRight: -1,
  },
  connectorRight: {
    marginLeft: -1,
  },
  connectorHidden: {
    backgroundColor: 'transparent',
  },
  connectorActive: {
    backgroundColor: colors.gold,
  },
  circle: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface,
    zIndex: 1,
  },
  circleActive: {
    borderColor: colors.gold,
    borderWidth: 2,
    backgroundColor: colors.surface,
  },
  circleCompleted: {
    borderColor: colors.gold,
    borderWidth: 2,
    backgroundColor: colors.goldSoft,
  },
  circleWaiting: {
    borderColor: '#333333',
    borderWidth: 1,
    backgroundColor: colors.surface,
  },
  circleNumber: {
    fontFamily: type.label,
    fontSize: 13,
    fontWeight: '700',
  },
  circleNumberActive: {
    color: colors.gold,
  },
  circleNumberWaiting: {
    color: colors.textMuted,
  },
  stepTitle: {
    fontFamily: type.bodyMedium,
    fontSize: 11,
    textAlign: 'center',
    minHeight: 28,
    lineHeight: 14,
    paddingHorizontal: 2,
  },
  stepTitleActive: {
    color: colors.text,
  },
  stepTitleWaiting: {
    color: colors.textMuted,
  },
  stepTime: {
    fontFamily: type.body,
    fontSize: 10,
    textAlign: 'center',
    marginTop: 2,
  },
  stepTimeActive: {
    color: colors.gold,
  },
  stepTimeWaiting: {
    color: colors.textMuted,
  },
  historySection: {
    borderTopWidth: 1,
    borderTopColor: '#262626',
    paddingTop: space.sm,
  },
  historyToggle: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: space.xs,
  },
  historyToggleLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
  },
  historyClock: {
    fontSize: 15,
    color: colors.gold,
  },
  historyToggleText: {
    color: colors.textSecondary,
    fontFamily: type.bodyMedium,
    fontSize: 14,
  },
  historyChevron: {
    color: colors.textSecondary,
    fontSize: 11,
  },
  historyList: {
    marginTop: space.sm,
    gap: space.xs,
  },
  historyItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 2,
  },
  historyLabel: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 13,
  },
  historyTime: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
  },
  historyEmpty: {
    color: colors.textMuted,
    fontFamily: type.body,
    fontSize: 12,
    fontStyle: 'italic',
  },
});
