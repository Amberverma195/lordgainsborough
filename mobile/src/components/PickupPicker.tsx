import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { FlatList, Modal, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space, type } from '../theme/tokens';
import {
  formatPickupDateLabel,
  formatPickupTimeLabel,
} from '../checkout/pickup';
import { usePickupSchedule } from '../checkout/usePickupSchedule';

export interface PickupPickerProps {
  date: string;
  time: string;
  onChange: (date: string, time: string) => void;
  refreshIntervalMs?: number;
  getNow?: () => Date;
}

export function PickupPicker({
  date,
  time,
  onChange,
  refreshIntervalMs = 30_000,
  getNow,
}: PickupPickerProps) {
  const [open, setOpen] = useState<'date' | 'time' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const { dates, times, refresh } = usePickupSchedule({
    selectedDate: date,
    selectedTime: time,
    refreshIntervalMs,
    getNow,
    onInvalidateTime: (expiredTime) => {
      onChange(date, '');
      const formatted = formatPickupTimeLabel(expiredTime);
      setNotice(`Selected pickup time (${formatted}) has passed. Please choose an upcoming time.`);
    },
    onInvalidateDate: () => {
      onChange('', '');
      setNotice('The selected pickup date has passed. Please choose an upcoming date.');
    },
  });

  const handleOpenDate = () => {
    refresh();
    setNotice(null);
    setOpen('date');
  };

  const handleOpenTime = () => {
    refresh();
    setNotice(null);
    setOpen('time');
  };

  return (
    <View style={styles.wrap}>
      <Text style={styles.label}>Pickup</Text>
      <Text style={styles.help}>Times follow restaurant operating hours in Toronto (from 11:00 AM).</Text>

      {notice ? (
        <View style={styles.noticeBox} accessibilityRole="alert">
          <Ionicons name="alert-circle-outline" size={18} color={colors.gold} />
          <Text style={styles.noticeText}>{notice}</Text>
        </View>
      ) : null}

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Choose pickup date"
        onPress={handleOpenDate}
        style={styles.field}
      >
        <Text style={styles.fieldLabel}>Date</Text>
        <Text style={styles.fieldValue}>{date ? formatPickupDateLabel(date) : 'Select a date'}</Text>
      </Pressable>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Choose pickup time"
        disabled={!date}
        onPress={handleOpenTime}
        style={[styles.field, !date && styles.disabled]}
      >
        <Text style={styles.fieldLabel}>Time</Text>
        <Text style={styles.fieldValue}>{time ? formatPickupTimeLabel(time) : 'Select a time'}</Text>
      </Pressable>

      <Modal visible={open !== null} animationType="slide" transparent onRequestClose={() => setOpen(null)}>
        <View style={styles.backdrop}>
          <View style={styles.sheet}>
            <Text style={styles.sheetTitle}>{open === 'date' ? 'Pickup date' : 'Pickup time'}</Text>
            <FlatList
              data={open === 'date' ? dates : times}
              keyExtractor={(item) => item}
              ListEmptyComponent={
                <Text style={styles.emptyText}>
                  {open === 'date'
                    ? 'No pickup dates are currently available.'
                    : 'No pickup times are available for this date.'}
                </Text>
              }
              renderItem={({ item }) => (
                <Pressable
                  accessibilityRole="button"
                  onPress={() => {
                    setNotice(null);
                    if (open === 'date') {
                      onChange(item, '');
                    } else {
                      onChange(date, item);
                    }
                    setOpen(null);
                  }}
                  style={styles.option}
                >
                  <Text style={styles.optionText}>
                    {open === 'date' ? formatPickupDateLabel(item) : formatPickupTimeLabel(item)}
                  </Text>
                </Pressable>
              )}
            />
            <Pressable accessibilityRole="button" onPress={() => setOpen(null)} style={styles.close}>
              <Text style={styles.closeText}>Close</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: space.sm,
  },
  label: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
  },
  help: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 14,
    lineHeight: 20,
  },
  noticeBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.xs,
    padding: space.sm,
    backgroundColor: colors.backgroundRaised,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.goldBorder,
  },
  noticeText: {
    color: colors.gold,
    fontFamily: type.bodyMedium,
    fontSize: 13,
    flex: 1,
  },
  field: {
    borderWidth: 1,
    borderColor: colors.goldBorder,
    borderRadius: radius.md,
    padding: space.md,
    backgroundColor: colors.backgroundRaised,
    gap: 4,
  },
  disabled: {
    opacity: 0.45,
  },
  fieldLabel: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  fieldValue: {
    color: colors.text,
    fontFamily: type.bodyMedium,
    fontSize: 16,
  },
  backdrop: {
    flex: 1,
    backgroundColor: colors.overlay,
    justifyContent: 'flex-end',
  },
  sheet: {
    maxHeight: '70%',
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    padding: space.lg,
    borderWidth: 1,
    borderColor: colors.goldBorder,
  },
  sheetTitle: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
    marginBottom: space.md,
  },
  emptyText: {
    color: colors.textMuted,
    fontFamily: type.body,
    fontSize: 14,
    paddingVertical: space.md,
    textAlign: 'center',
  },
  option: {
    minHeight: 48,
    justifyContent: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  optionText: {
    color: colors.text,
    fontFamily: type.body,
    fontSize: 16,
  },
  close: {
    marginTop: space.md,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeText: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 16,
  },
});
