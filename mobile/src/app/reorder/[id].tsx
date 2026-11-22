import { useQuery } from '@tanstack/react-query';
import { useLocalSearchParams, router } from 'expo-router';
import { useRef, useState, useEffect } from 'react';
import { Text } from 'react-native';
import { fetchOrder } from '../../api/orders';
import { fetchMenu } from '../../api/catalog';
import { useAuth } from '../../auth/AuthContext';
import { useCart } from '../../cart/CartContext';
import { planReorder } from '../../menu/discovery';
import { Screen } from '../../components/Screen';
import { AppHeader } from '../../components/AppHeader';
import { Button } from '../../components/Button';
import { colors } from '../../theme/tokens';
export default function ReorderScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { user, status } = useAuth();
  const { addItem } = useCart();
  const [notice, setNotice] = useState('');
  const [added, setAdded] = useState(false);
  const locked = useRef(false);
  const active = useRef(user?.token);
  useEffect(() => {
    active.current = user?.token;
  }, [user?.token]);
  const data = useQuery({
    queryKey: ['reorder', id, user?.token],
    enabled: status === 'signedIn' && !!user && typeof id === 'string',
    queryFn: async () => {
      const [order, menu] = await Promise.all([
        fetchOrder(id, user!.token),
        fetchMenu(),
      ]);
      if (order.status !== 'PICKED_UP')
        throw new Error('Only completed pickup orders can be reordered.');
      return planReorder(order.items, menu);
    },
  });
  function add() {
    if (!data.data || locked.current || !user || active.current !== user.token)
      return;
    locked.current = true;
    const messages: string[] = [];
    for (const line of data.data.available) {
      const issue = addItem(line.item, line.quantity);
      if (issue) messages.push(issue);
    }
    setAdded(true);
    setNotice(
      messages.length
        ? messages.join(' ')
        : 'Available dishes added at current prices. Choose pickup details and confirm at checkout.',
    );
  }
  return (
    <Screen scroll>
      <AppHeader title="Review reorder" showBack />
      {!user ? (
        <Text style={{ color: colors.text }}>
          Sign in to reorder your own completed meals.
        </Text>
      ) : null}
      {data.isLoading ? (
        <Text style={{ color: colors.text }}>
          Checking current menu and prices...
        </Text>
      ) : null}
      {data.isError ? (
        <>
          <Text style={{ color: colors.danger }}>
            {data.error instanceof Error
              ? data.error.message
              : 'Unable to load this order.'}
          </Text>
          <Button label="Retry" onPress={() => void data.refetch()} />
        </>
      ) : null}
      {data.data ? (
        <>
          <Text style={{ color: colors.text }}>
            Review current dishes and prices. Adding to your cart does not place
            an order.
          </Text>
          {data.data.notices.map((text, i) => (
            <Text key={i} style={{ color: colors.gold }}>
              {text}
            </Text>
          ))}
          {data.data.available.map(({ item, quantity }) => (
            <Text
              key={item.id}
              style={{ color: colors.text, paddingVertical: 8 }}
            >
              {quantity} x {item.name} - $ {item.price.toFixed(2)} each
            </Text>
          ))}
          {!data.data.available.length ? (
            <Text style={{ color: colors.text }}>
              None of these dishes are currently available.
            </Text>
          ) : null}
          <Button
            label="Add available dishes to cart"
            disabled={added || !data.data.available.length}
            onPress={add}
          />
        </>
      ) : null}
      {notice ? (
        <Text accessibilityRole="alert" style={{ color: colors.text }}>
          {notice}
        </Text>
      ) : null}
      {added ? (
        <Button
          label="Review cart"
          onPress={() => router.replace('/(tabs)/cart')}
        />
      ) : null}
    </Screen>
  );
}
