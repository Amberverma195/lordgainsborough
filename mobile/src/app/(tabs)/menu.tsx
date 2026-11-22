import { useAuth } from '../../auth/AuthContext';
import { useFavourites } from '../../menu/useFavourites';
import { filterDishes } from '../../menu/discovery';
import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import {
  FlatList,
  TextInput,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

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
  const { user } = useAuth();
  const favourites = useFavourites(user?.email);
  const [search, setSearch] = useState('');
  const [onlyFavourites, setOnlyFavourites] = useState(false);
  const [category, setCategory] = useState('All');
  const menu = useQuery({
    queryKey: ['menu'],
    queryFn: ({ signal }) => fetchMenu(signal),
  });
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: ({ signal }) => fetchCategories(signal),
  });
  const filters = useMemo(
    () => ['All', ...categoryFilters(categories.data ?? [], menu.data ?? [])],
    [categories.data, menu.data],
  );
  const visible = filterDishes(
    menu.data ?? [],
    search,
    category,
    onlyFavourites ? favourites.ids : undefined,
  );

  return (
    <Screen padded={false}>
      <View style={styles.headerPad}>
        <AppHeader title="Menu" eyebrow="Lord Gainsborough" cartCount={count} />
      </View>
      <View style={styles.headerPad}>
        <TextInput
          accessibilityLabel="Search dishes"
          placeholder="Search names and descriptions"
          placeholderTextColor={colors.textMuted}
          value={search}
          onChangeText={setSearch}
          style={{ color: colors.text, minHeight: 48 }}
        />
        <Pressable
          accessibilityRole="button"
          accessibilityState={{ selected: onlyFavourites }}
          onPress={() => setOnlyFavourites((value) => !value)}
        >
          <Text style={styles.chipText}>
            {onlyFavourites ? 'Show all dishes' : 'Favourites'}
          </Text>
        </Pressable>
        {favourites.error ? (
          <Text style={styles.note}>{favourites.error}</Text>
        ) : null}
        {onlyFavourites ? (
          <>
            <Text style={styles.note}>
              Saved on this device for this account. Discontinued dishes may no
              longer appear.
            </Text>
            {favourites.ids
              .filter((id) => !menu.data?.some((item) => item.id === id))
              .map((id) => (
                <Pressable
                  key={id}
                  accessibilityRole="button"
                  onPress={() => favourites.toggle(id)}
                >
                  <Text style={styles.note}>
                    Remove discontinued favourite {id}
                  </Text>
                </Pressable>
              ))}
          </>
        ) : null}
      </View>
      {menu.isLoading ? (
        <View style={styles.headerPad}>
          <LoadingState label="Loading the menu" />
        </View>
      ) : null}
      {menu.isError ? (
        <View style={styles.headerPad}>
          <ErrorState
            title="Menu unavailable"
            message={
              menu.error instanceof ApiError
                ? menu.error.message
                : 'The menu could not be loaded.'
            }
            onRetry={() => void menu.refetch()}
          />
        </View>
      ) : null}
      {menu.data ? (
        <FlatList
          data={visible}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.list}
          ListHeaderComponent={
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
                    <Text
                      style={[
                        styles.chipText,
                        selected && styles.chipTextSelected,
                      ]}
                    >
                      {filter}
                    </Text>
                  </Pressable>
                );
              })}
              {categories.isError ? (
                <Text style={styles.note}>
                  Categories could not be loaded. Dishes are still grouped from
                  the menu.
                </Text>
              ) : null}
            </View>
          }
          ListEmptyComponent={
            <Text style={styles.note}>
              No dishes match these filters. Try another search or category.
            </Text>
          }
          renderItem={({ item }) => (
            <MenuItemCard
              item={item}
              favourite={favourites.ids.includes(item.id)}
              onToggleFavourite={
                favourites.ready ? () => favourites.toggle(item.id) : undefined
              }
              onPress={() => router.push(`/menu/${item.id}`)}
            />
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
