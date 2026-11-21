import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { clearAllConfirmedOrders } from '../checkout/confirmationCache';
import { queryClient } from '../query/client';
import {
  SESSION_TOKEN_KEY,
  getTrackingIndexStorageKey,
  getLegacyV1TrackingIndexStorageKey,
  getTrackingStorageKey,
  getLegacyTrackingStorageKey,
  getLegacyV1TrackingStorageKey,
} from './secureSession';

export async function clearDeletedAccountData(email: string) {
  await queryClient.cancelQueries();
  queryClient.clear();
  clearAllConfirmedOrders();
  const jobs: Promise<unknown>[] = [
    SecureStore.deleteItemAsync(SESSION_TOKEN_KEY),
  ];
  for (const account of [email, null]) {
    for (const index of [
      getTrackingIndexStorageKey(account),
      getLegacyV1TrackingIndexStorageKey(account),
    ]) {
      jobs.push(
        (async () => {
          const raw = await SecureStore.getItemAsync(index);
          const ids: unknown = raw ? JSON.parse(raw) : [];
          if (!Array.isArray(ids))
            throw new Error('Unable to read saved tracking credentials');
          await Promise.all(
            ids
              .filter((id): id is string => typeof id === 'string')
              .flatMap((id) => [
                getTrackingStorageKey(id, account),
                getLegacyTrackingStorageKey(id),
                getLegacyV1TrackingStorageKey(id, account),
              ])
              .map((key) => SecureStore.deleteItemAsync(key)),
          );
          await SecureStore.deleteItemAsync(index);
        })(),
      );
    }
  }
  jobs.push(
    (async () => {
      const keys = await AsyncStorage.getAllKeys();
      await AsyncStorage.multiRemove(
        keys.filter((key) => /^lgb\.(cart|pickup|checkout)\./.test(key)),
      );
    })(),
  );
  const results = await Promise.allSettled(jobs);
  if (results.some((result) => result.status === 'rejected'))
    throw new Error(
      'Account deleted. Device cleanup could not finish. Close the app and clear its app data before sharing this device.',
    );
}
