import { useMemo, useState } from 'react';
import { FlatList, Modal, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space, type } from '../theme/tokens';
import {
  formatPickupDateLabel,
  formatPickupTimeLabel,
  pickupChoices,
  pickupDateChoices,
} from '../checkout/pickup';

export function PickupPicker({
  date,
  time,
  onChange,
}: {
  date: string;
  time: string;
  onChange: (date: string, time: string) => void;
}) {
  const [open, setOpen] = useState<'date' | 'time' | null>(null);
  const dates = useMemo(() => pickupDateChoices(), []);
  const times = useMemo(() => (date ? pickupChoices(date) : []), [date]);

  return (
    <View style={styles.wrap}>
      <Text style={styles.label}>Pickup</Text>
      <Text style={styles.help}>Times follow restaurant operating hours in Toronto (from 11:00 AM).</Text>
      <Pressable accessibilityRole="button" accessibilityLabel="Choose pickup date" onPress={() => setOpen('date')} style={styles.field}>
        <Text style={styles.fieldLabel}>Date</Text>
        <Text style={styles.fieldValue}>{date ? formatPickupDateLabel(date) : 'Select a date'}</Text>
      </Pressable>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Choose pickup time"
        disabled={!date}
        onPress={() => setOpen('time')}
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
              renderItem={({ item }) => (
                <Pressable
                  accessibilityRole="button"
                  onPress={() => {
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
