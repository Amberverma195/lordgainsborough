import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { clearDeletedAccountData } from '../deleteAccountData';
import {
  getTrackingIndexStorageKey,
  getTrackingStorageKey,
  SESSION_TOKEN_KEY,
} from '../secureSession';
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));
jest.mock('../../query/client', () => ({
  queryClient: { cancelQueries: jest.fn(), clear: jest.fn() },
}));
jest.mock('../../checkout/confirmationCache', () => ({
  clearAllConfirmedOrders: jest.fn(),
}));
it('removes session, indexed tracking tokens, carts, pickup selections and checkout keys', async () => {
  await AsyncStorage.multiSet([
    ['lgb.cart.u.test.v2', 'cart'],
    ['lgb.checkout.u.test', 'key'],
    ['lgb.pickup.u.test', 'time'],
    ['other-app', 'keep'],
  ]);
  (SecureStore.getItemAsync as jest.Mock).mockImplementation(async (key) =>
    key === getTrackingIndexStorageKey('member@example.com')
      ? '["order-1"]'
      : null,
  );
  await clearDeletedAccountData('member@example.com');
  expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith(SESSION_TOKEN_KEY);
  expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith(
    getTrackingStorageKey('order-1', 'member@example.com'),
  );
  expect(await AsyncStorage.getAllKeys()).toEqual(['other-app']);
});
