import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { reservationDates } from '../reservations/choices';
import { colors } from '../theme/tokens';
export function ReservationCalendar({
  selected,
  onSelect,
}: {
  selected: string;
  onSelect: (date: string) => void;
}) {
  const dates = reservationDates();
  const min = dates[0].slice(0, 7),
    max = dates[dates.length - 1].slice(0, 7);
  const [month, setMonth] = useState(
    selected && dates.includes(selected) ? selected.slice(0, 7) : min,
  );
  const first = new Date(month + '-01T12:00:00Z');
  const days = new Date(
    Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0),
  ).getUTCDate();
  function move(delta: number) {
    const next = new Date(first);
    next.setUTCMonth(next.getUTCMonth() + delta);
    setMonth(next.toISOString().slice(0, 7));
  }
  return (
    <View>
      <View
        style={{
          flexDirection: 'row',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Previous reservation month"
          disabled={month <= min}
          onPress={() => move(-1)}
          style={{ padding: 12 }}
        >
          <Text style={{ color: colors.gold }}>Previous</Text>
        </Pressable>
        <Text accessibilityRole="header" style={{ color: colors.text }}>
          {new Intl.DateTimeFormat('en-CA', {
            month: 'long',
            year: 'numeric',
            timeZone: 'UTC',
          }).format(first)}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Next reservation month"
          disabled={month >= max}
          onPress={() => move(1)}
          style={{ padding: 12 }}
        >
          <Text style={{ color: colors.gold }}>Next</Text>
        </Pressable>
      </View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
        {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((day) => (
          <Text
            key={day}
            style={{
              width: '14.28%',
              textAlign: 'center',
              color: colors.textSecondary,
            }}
          >
            {day}
          </Text>
        ))}
        {Array.from({ length: first.getUTCDay() }, (_, index) => (
          <View key={'blank' + index} style={{ width: '14.28%' }} />
        ))}
        {Array.from({ length: days }, (_, index) => {
          const day = index + 1,
            date = month + '-' + String(day).padStart(2, '0');
          return (
            <Pressable
              key={date}
              accessibilityRole="button"
              accessibilityLabel={'Select ' + date}
              accessibilityState={{
                selected: date === selected,
                disabled: !dates.includes(date),
              }}
              disabled={!dates.includes(date)}
              onPress={() => onSelect(date)}
              style={{
                width: '14.28%',
                minHeight: 44,
                justifyContent: 'center',
              }}
            >
              <Text
                style={{
                  textAlign: 'center',
                  color: dates.includes(date) ? colors.gold : colors.textMuted,
                }}
              >
                {day}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
