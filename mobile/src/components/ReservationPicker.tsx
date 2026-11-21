import { useState } from 'react';
import { FlatList, Modal, Pressable, Text, View } from 'react-native';
import { reservationDates, reservationTimes } from '../reservations/choices';
import {
  formatPickupDateLabel,
  formatPickupTimeLabel,
} from '../checkout/pickup';
import { colors } from '../theme/tokens';
export function ReservationPicker({
  date,
  time,
  partySize,
  disabled,
  onChange,
}: {
  date: string;
  time: string;
  partySize: string;
  disabled: boolean;
  onChange: (value: { date: string; time: string; partySize: string }) => void;
}) {
  const [open, setOpen] = useState<'date' | 'time' | 'partySize' | null>(null);
  const choices =
    open === 'date'
      ? reservationDates()
      : open === 'time'
        ? reservationTimes(date)
        : Array.from({ length: 10 }, (_, i) => String(i + 1));
  const label = (value: string) =>
    open === 'date'
      ? formatPickupDateLabel(value)
      : open === 'time'
        ? formatPickupTimeLabel(value)
        : value + ' guests';
  return (
    <View>
      <Text style={{ color: colors.textSecondary }}>
        Choose a Toronto date and local dining time. Times shown are request
        slots, not live table availability. The restaurant validates requests
        and confirms availability.
      </Text>
      {(['date', 'time', 'partySize'] as const).map((field) => (
        <Pressable
          key={field}
          accessibilityRole="button"
          accessibilityLabel={'Choose reservation ' + field}
          disabled={disabled || (field === 'time' && !date)}
          onPress={() => setOpen(field)}
          style={{ padding: 16 }}
        >
          <Text style={{ color: colors.text }}>
            {field === 'date'
              ? date
                ? formatPickupDateLabel(date)
                : 'Select a date'
              : field === 'time'
                ? time
                  ? formatPickupTimeLabel(time)
                  : 'Select a time'
                : partySize + ' guests'}
          </Text>
        </Pressable>
      ))}
      <Modal
        visible={open !== null}
        animationType="slide"
        onRequestClose={() => setOpen(null)}
      >
        <View
          style={{ flex: 1, backgroundColor: colors.background, padding: 24 }}
        >
          <Text style={{ color: colors.text }}>
            Toronto reservation choices
          </Text>
          <FlatList
            data={choices}
            keyExtractor={(value) => value}
            ListEmptyComponent={
              <Text style={{ color: colors.text }}>
                No upcoming slots. Choose another date or call the restaurant.
              </Text>
            }
            renderItem={({ item }) => (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={'Select ' + item}
                onPress={() => {
                  if (open === 'date')
                    onChange({ date: item, time: '', partySize });
                  else if (
                    open === 'time' &&
                    reservationTimes(date).includes(item)
                  )
                    onChange({ date, time: item, partySize });
                  else if (open === 'partySize')
                    onChange({ date, time, partySize: item });
                  setOpen(null);
                }}
                style={{ padding: 16 }}
              >
                <Text style={{ color: colors.text }}>{label(item)}</Text>
              </Pressable>
            )}
          />
          <Pressable accessibilityRole="button" onPress={() => setOpen(null)}>
            <Text style={{ color: colors.text, padding: 16 }}>
              Close choices
            </Text>
          </Pressable>
        </View>
      </Modal>
    </View>
  );
}
