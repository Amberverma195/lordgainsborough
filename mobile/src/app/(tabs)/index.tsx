import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { fetchMostLoved, fetchMenu, fetchStoreSettings } from '../../api/catalog';
import { ApiError } from '../../api/errors';
import { MenuItem } from '../../api/parse';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { DishImage } from '../../components/DishImage';
import { ErrorState } from '../../components/States';
import { Price } from '../../components/Price';
import { Screen } from '../../components/Screen';
import { restaurantFacts } from '../../restaurant/facts';
import { colors, space, type } from '../../theme/tokens';

async function loadFeatured(signal?: AbortSignal): Promise<MenuItem[]> {
  try {
    const loved = (await fetchMostLoved(signal)).filter((item) => item.available);
    if (loved.length > 0) {
      return loved;
    }
  } catch (error) {
    if (signal?.aborted) {
      throw error;
    }
  }
  return (await fetchMenu(signal)).filter((item) => item.available).slice(0, 5);
}

export default function HomeScreen() {
  const featured = useQuery({ queryKey: ['featured'], queryFn: ({ signal }) => loadFeatured(signal) });
  const settings = useQuery({ queryKey: ['settings'], queryFn: ({ signal }) => fetchStoreSettings(signal) });
  const phone = settings.data?.contactPhone || restaurantFacts.phoneDisplay;
  const name = settings.data?.storeName || restaurantFacts.name;

  return (
    <Screen scroll>
      <View style={styles.hero}>
        <Text style={styles.eyebrow}>Established {restaurantFacts.established}</Text>
        <Text style={styles.title}>{name}</Text>
        <View style={styles.rule} />
        <Text style={styles.subtitle}>{restaurantFacts.tagline}</Text>
        <View style={styles.actions}>
          <Button label="Explore Menu" onPress={() => router.push('/(tabs)/menu')} />
          <Button label="Book a Table" variant="secondary" onPress={() => router.push('/reserve')} />
        </View>
      </View>

      <Text style={styles.section}>Featured dishes</Text>
      {featured.isLoading ? <Text style={styles.muted}>Loading the menu…</Text> : null}
      {featured.isError ? (
        <ErrorState
          title="Menu unavailable"
          message={featured.error instanceof ApiError ? featured.error.message : 'The menu could not be loaded.'}
          onRetry={() => void featured.refetch()}
        />
      ) : null}
      {featured.data && featured.data.length === 0 ? (
        <Text style={styles.muted}>No featured dishes are available right now.</Text>
      ) : null}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.featuredRow}>
        {featured.data?.map((item) => (
          <Pressable key={item.id} accessibilityRole="button" onPress={() => router.push(`/menu/${item.id}`)} style={styles.featuredCard}>
            <DishImage image={item.image} title={item.name} height={140} />
            <View style={styles.featuredBody}>
              <Text style={styles.dishName} numberOfLines={1}>{item.name}</Text>
              <Price amount={item.price} />
            </View>
          </Pressable>
        ))}
      </ScrollView>

      <Text style={styles.section}>Visit</Text>
      <View style={styles.infoStack}>
        <Card>
          <Text style={styles.cardTitle}>Hours</Text>
          {restaurantFacts.diningHours.map((line) => <Text key={line} style={styles.body}>{line}</Text>)}
          <Text style={styles.goldNote}>{restaurantFacts.pickupHours}</Text>
        </Card>
        <Card>
          <Text style={styles.cardTitle}>Address</Text>
          {restaurantFacts.addressLines.map((line) => <Text key={line} style={styles.body}>{line}</Text>)}
          <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(restaurantFacts.mapsUrl)}>
            <Text style={styles.link}>Get directions</Text>
          </Pressable>
        </Card>
        <Card>
          <Text style={styles.cardTitle}>Contact</Text>
          <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(`tel:${restaurantFacts.phoneTel}`)}>
            <Text style={styles.link}>{phone}</Text>
          </Pressable>
          {settings.data?.contactEmail ? <Text style={styles.body}>{settings.data.contactEmail}</Text> : null}
          <Text style={styles.body}>{restaurantFacts.paymentNote}</Text>
        </Card>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  hero: {
    paddingTop: space.xl,
    paddingBottom: space.xl,
    gap: space.md,
  },
  eyebrow: {
    color: colors.gold,
    fontFamily: type.label,
    letterSpacing: 2.4,
    textTransform: 'uppercase',
    fontSize: 12,
  },
  title: {
    color: colors.text,
    fontFamily: type.display,
    fontSize: 48,
    lineHeight: 52,
  },
  rule: {
    width: 56,
    height: 1,
    backgroundColor: colors.gold,
  },
  subtitle: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 18,
    lineHeight: 26,
  },
  actions: {
    gap: space.sm,
    marginTop: space.md,
  },
  section: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 28,
    marginBottom: space.md,
  },
  muted: {
    color: colors.textSecondary,
    fontFamily: type.body,
    marginBottom: space.md,
  },
  featuredRow: {
    gap: space.md,
    paddingBottom: space.xl,
  },
  featuredCard: {
    width: 220,
    borderRadius: 22,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: colors.goldBorder,
    backgroundColor: colors.surface,
  },
  featuredBody: {
    padding: space.md,
    gap: space.sm,
  },
  dishName: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 18,
  },
  infoStack: {
    gap: space.md,
  },
  cardTitle: {
    color: colors.text,
    fontFamily: type.heading,
    fontSize: 20,
    marginBottom: space.sm,
  },
  body: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 15,
    lineHeight: 22,
  },
  goldNote: {
    color: colors.gold,
    fontFamily: type.bodyMedium,
    marginTop: space.sm,
  },
  link: {
    color: colors.gold,
    fontFamily: type.label,
    fontSize: 16,
    marginVertical: space.sm,
  },
});
