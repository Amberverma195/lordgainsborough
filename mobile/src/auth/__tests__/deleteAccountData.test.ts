import { getCartStorageKey, getPickupStorageKey } from '../../cart/storage';
import { queryClient } from '../../query/client';
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
beforeEach(async () => {jest.clearAllMocks(); await AsyncStorage.clear();});
it('removes session, indexed tracking tokens, carts, pickup selections and checkout keys', async () => {
  await AsyncStorage.multiSet([
    [getCartStorageKey('member@example.com'), 'cart'],
    [getCartStorageKey('other@example.com'), 'keep'],
    [getPickupStorageKey('member@example.com'), 'time'],
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
  expect(await AsyncStorage.getAllKeys()).toEqual(expect.arrayContaining(['other-app', getCartStorageKey('other@example.com')]));
  expect(await AsyncStorage.getItem(getCartStorageKey('member@example.com'))).toBeNull();
});

it('continues independent cleanup on failures, preserves failed indexes and safely retries', async () => {
 (SecureStore.getItemAsync as jest.Mock).mockImplementation(async key => key === getTrackingIndexStorageKey('member@example.com') ? '["order-1"]' : null);
 (queryClient.cancelQueries as jest.Mock).mockRejectedValueOnce(new Error('cancel failed'));
 (SecureStore.deleteItemAsync as jest.Mock).mockImplementation(async key => {if(key === getTrackingStorageKey('order-1','member@example.com')) throw new Error('locked');});
 const detach=jest.fn();
 await expect(clearDeletedAccountData('member@example.com', detach)).rejects.toThrow('cleanup');
 expect(detach).toHaveBeenCalledTimes(1);
 expect(SecureStore.deleteItemAsync).not.toHaveBeenCalledWith(getTrackingIndexStorageKey('member@example.com'));
 expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith(SESSION_TOKEN_KEY);
 (SecureStore.deleteItemAsync as jest.Mock).mockResolvedValue(undefined);
 await expect(clearDeletedAccountData('member@example.com')).resolves.toBeUndefined();
 await expect(clearDeletedAccountData('member@example.com')).resolves.toBeUndefined();
});
