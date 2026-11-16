import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { FlatList, Pressable, StyleSheet, Text, View } from 'react-native';

import { categoryFilters, fetchCategories, fetchMenu } from '../../api/catalog';
import { ApiError } from '../../api/errors';
import { AppHeader } from '../../components/AppHeader';
import { MenuItemCard } from '../../components/MenuItemCard';
import { ErrorState, LoadingState } from '../../components/States';
import { Screen } from '../../components/Screen';
import { useCart } from '../../cart/CartContext';
import { colors, radius, space, type } from '../../theme/tokens';

export default function MenuScreen() {
  const { count } = useCart();
  const [category, setCategory] = useState('All');
  const menu = useQuery({ queryKey: ['menu'], queryFn: ({ signal }) => fetchMenu(signal) });
  const categories = useQuery({ queryKey: ['categories'], queryFn: ({ signal }) => fetchCategories(signal) });
  const filters = useMemo(
    () => ['All', ...categoryFilters(categories.data ?? [], menu.data ?? [])],
    [categories.data, menu.data],
  );
  const visible = (menu.data ?? []).filter((item) => category === 'All' || item.category === category);

  return (
    <Screen padded={false}>
      <View style={styles.headerPad}>
        <AppHeader title="Menu" eyebrow="Lord Gainsborough" cartCount={count} />
      </View>
      {menu.isLoading ? <View style={styles.headerPad}><LoadingState label="Loading the menu" /></View> : null}
      {menu.isError ? (
        <View style={styles.headerPad}>
          <ErrorState
            title="Menu unavailable"
            message={menu.error instanceof ApiError ? menu.error.message : 'The menu could not be loaded.'}
            onRetry={() => void menu.refetch()}
          />
        </View>
      ) : null}
      {menu.data ? (
        <FlatList
          data={visible}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.list}
          ListHeaderComponent={(
            <View style={styles.filters}>
              {filters.map((filter) => {
                const selected = filter === category;
                return (
                  <Pressable
                    key={filter}
                    accessibilityRole="button"
                    accessibilityState={{ selected }}
                    onPress={() => setCategory(filter)}
                    style={[styles.chip, selected && styles.chipSelected]}
                  >
                    <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{filter}</Text>
                  </Pressable>
                );
              })}
              {categories.isError ? (
                <Text style={styles.note}>Categories could not be loaded. Dishes are still grouped from the menu.</Text>
              ) : null}
            </View>
          )}
          ListEmptyComponent={<Text style={styles.note}>No dishes are listed in this category.</Text>}
          renderItem={({ item }) => (
            <MenuItemCard item={item} onPress={() => router.push(`/menu/${item.id}`)} />
          )}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  headerPad: {
    paddingHorizontal: space.lg,
  },
  list: {
    paddingHorizontal: space.lg,
    paddingBottom: space.xxl,
  },
  filters: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.sm,
    marginBottom: space.lg,
  },
  chip: {
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
    paddingHorizontal: 14,
    minHeight: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chipSelected: {
    borderColor: colors.gold,
    backgroundColor: colors.goldSoft,
  },
  chipText: {
    color: colors.textSecondary,
    fontFamily: type.label,
  },
  chipTextSelected: {
    color: colors.gold,
  },
  note: {
    color: colors.textSecondary,
    fontFamily: type.body,
    marginBottom: space.md,
  },
});
