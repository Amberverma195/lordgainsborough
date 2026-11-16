import { useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { fetchMenu } from '../../api/catalog';
import { ApiError } from '../../api/errors';
import { useCart } from '../../cart/CartContext';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { DishImage } from '../../components/DishImage';
import { Price } from '../../components/Price';
import { QuantityStepper } from '../../components/QuantityStepper';
import { Screen } from '../../components/Screen';
import { ErrorState, LoadingState } from '../../components/States';
import { colors, space, type } from '../../theme/tokens';

export default function MenuItemScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const itemId = Array.isArray(id) ? id[0] : id;
  const { addItem, count } = useCart();
  const [quantity, setQuantity] = useState(1);
  const [message, setMessage] = useState<string | null>(null);
  const menu = useQuery({ queryKey: ['menu'], queryFn: ({ signal }) => fetchMenu(signal) });
  const item = menu.data?.find((entry) => entry.id === itemId);

  return (
    <Screen scroll>
      <AppHeader title="Dish" showBack cartCount={count} />
      {menu.isLoading ? <LoadingState label="Loading this dish" /> : null}
      {menu.isError ? (
        <ErrorState
          title="Dish unavailable"
          message={menu.error instanceof ApiError ? menu.error.message : 'This dish could not be loaded.'}
          onRetry={() => void menu.refetch()}
        />
      ) : null}
      {menu.data && !item ? (
        <ErrorState title="Dish not on the menu" message="This dish is no longer listed. Return to the menu and choose another." onRetry={() => router.back()} />
      ) : null}
      {item ? (
        <View style={styles.content}>
          <View style={styles.imageWrap}>
            <DishImage image={item.image} title={item.name} height={260} />
          </View>
          <Text style={styles.name}>{item.name}</Text>
          {item.category ? <Text style={styles.category}>{item.category}</Text> : null}
          <Price amount={item.price} large />
          {item.description ? <Text style={styles.description}>{item.description}</Text> : null}
          <Text style={item.available ? styles.available : styles.unavailable}>
            {item.available ? 'Available for pickup' : 'Currently unavailable'}
          </Text>
          {item.available ? (
            <QuantityStepper quantity={quantity} onChange={(next) => setQuantity(Math.max(1, next))} />
          ) : null}
          {message ? <Text style={styles.message}>{message}</Text> : null}
          <Button
            label={item.available ? 'Add to cart' : 'Unavailable'}
            disabled={!item.available}
            onPress={() => {
              const error = addItem(item, quantity);
              setMessage(error ?? 'Added to your cart.');
              if (!error) {
                router.push('/(tabs)/cart');
              }
            }}
          />
        </View>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: {
    gap: space.md,
  },
  imageWrap: {
    borderRadius: 22,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: colors.goldBorder,
  },
  name: {
    color: colors.text,
    fontFamily: type.display,
    fontSize: 36,
    lineHeight: 40,
  },
  category: {
    color: colors.gold,
    fontFamily: type.label,
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  description: {
    color: colors.textSecondary,
    fontFamily: type.body,
    fontSize: 16,
    lineHeight: 24,
  },
  available: {
    color: colors.success,
    fontFamily: type.label,
  },
  unavailable: {
    color: colors.danger,
    fontFamily: type.label,
  },
  message: {
    color: colors.textSecondary,
    fontFamily: type.body,
  },
});
