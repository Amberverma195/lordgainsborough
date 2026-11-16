import { Linking, StyleSheet, Text } from 'react-native';

import { AppHeader } from '../components/AppHeader';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Screen } from '../components/Screen';
import { restaurantFacts } from '../restaurant/facts';
import { colors, space, type } from '../theme/tokens';

export default function ReserveScreen() {
  return (
    <Screen scroll>
      <AppHeader title="Book a Table" eyebrow="Reservations" showBack />
      <Text style={styles.copy}>
        Table requests from the mobile app are part of a later release. Call the restaurant and the team will confirm your booking.
      </Text>
      <Card>
        <Text style={styles.heading}>{restaurantFacts.name}</Text>
        {restaurantFacts.addressLines.map((line) => <Text key={line} style={styles.copy}>{line}</Text>)}
        <Text style={styles.heading}>Dining hours</Text>
        {restaurantFacts.diningHours.map((line) => <Text key={line} style={styles.copy}>{line}</Text>)}
        <Text style={styles.note}>{restaurantFacts.privateDining}</Text>
      </Card>
      <Button label={`Call ${restaurantFacts.phoneDisplay}`} onPress={() => void Linking.openURL(`tel:${restaurantFacts.phoneTel}`)} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  copy: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
    marginBottom: space.sm,
  },
  heading: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 22,
    marginTop: space.sm,
    marginBottom: space.sm,
  },
  note: {
    color: colors.gold,
    fontFamily: type.body,
    lineHeight: 22,
    marginTop: space.md,
  },
});
