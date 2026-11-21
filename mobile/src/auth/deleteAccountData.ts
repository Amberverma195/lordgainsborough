import { toAccountNamespace } from './accountNamespace';
import { getCartStorageKey, getPickupStorageKey, getLegacyV1CartStorageKey, getLegacyV1PickupStorageKey } from '../cart/storage';
import { getCheckoutIdempotencyStorageKey, getCheckoutFingerprintStorageKey, getLegacyV1CheckoutIdempotencyStorageKey, getLegacyV1CheckoutFingerprintStorageKey } from '../checkout/idempotency';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import { clearAllConfirmedOrders } from '../checkout/confirmationCache';
import { queryClient } from '../query/client';
import {
  SESSION_TOKEN_KEY,
  getTrackingIndexStorageKey,
  getLegacyV1TrackingIndexStorageKey,
  getTrackingStorageKey,
  getLegacyV1TrackingStorageKey,
} from './secureSession';

export async function clearDeletedAccountData(email: string, detach: () => void = () => {}) {
  detach();
  const cancel = queryClient.cancelQueries();
  queryClient.clear();
  clearAllConfirmedOrders();
  const jobs: Promise<unknown>[] = [
    cancel,
    SecureStore.deleteItemAsync(SESSION_TOKEN_KEY),
  ];
  for (const account of [email]) {
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
      await AsyncStorage.multiRemove([
        getCartStorageKey(email), getPickupStorageKey(email),
        getLegacyV1CartStorageKey(email), getLegacyV1PickupStorageKey(email),
        getCheckoutIdempotencyStorageKey(email), getCheckoutFingerprintStorageKey(email),
        getLegacyV1CheckoutIdempotencyStorageKey(email), getLegacyV1CheckoutFingerprintStorageKey(email),
        'lgb.checkout.pending.' + toAccountNamespace(email),
      ]);
    })(),
  );
  const results = await Promise.allSettled(jobs);
  if (results.some((result) => result.status === 'rejected'))
    throw new Error(
      'Account deleted. Device cleanup could not finish. Close the app and clear its app data before sharing this device.',
    );
}
