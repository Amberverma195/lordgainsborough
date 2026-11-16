import { Inter_400Regular, Inter_500Medium, Inter_600SemiBold } from '@expo-google-fonts/inter';
import { Outfit_600SemiBold, Outfit_700Bold } from '@expo-google-fonts/outfit';
import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { AppState, type AppStateStatus, Platform } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { focusManager, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { fetchMenu } from '../api/catalog';
import { AuthProvider } from '../auth/AuthContext';
import { CartProvider, useCart } from '../cart/CartContext';
import { queryClient } from '../query/client';
import { colors } from '../theme/tokens';

function CatalogSync() {
  const { reconcile, ready } = useCart();
  const menu = useQuery({ queryKey: ['menu'], queryFn: ({ signal }) => fetchMenu(signal) });
  useEffect(() => {
    if (ready && menu.data) {
      reconcile(menu.data);
    }
  }, [menu.data, ready, reconcile]);
  return null;
}

export default function RootLayout() {
  const [loaded, error] = useFonts({
    Outfit_600SemiBold,
    Outfit_700Bold,
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
  });

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (status: AppStateStatus) => {
      if (Platform.OS !== 'web') {
        focusManager.setFocused(status === 'active');
      }
    });
    return () => subscription.remove();
  }, []);

  if (!loaded && !error) {
    return null;
  }

  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: colors.background }}>
      <SafeAreaProvider>
        <QueryClientProvider client={queryClient}>
          <AuthProvider>
            <CartProvider>
              <CatalogSync />
              <StatusBar style="light" />
              <Stack
                screenOptions={{
                  headerShown: false,
                  contentStyle: { backgroundColor: colors.background },
                  animation: 'fade',
                }}
              >
                <Stack.Screen name="(tabs)" />
                <Stack.Screen name="menu/[id]" />
                <Stack.Screen name="checkout" />
                <Stack.Screen name="order/[id]" />
                <Stack.Screen name="auth/login" options={{ presentation: 'modal' }} />
                <Stack.Screen name="auth/signup" options={{ presentation: 'modal' }} />
                <Stack.Screen name="auth/forgot-password" options={{ presentation: 'modal' }} />
                <Stack.Screen name="profile/edit" options={{ presentation: 'modal' }} />
                <Stack.Screen name="reserve" />
              </Stack>
            </CartProvider>
          </AuthProvider>
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
