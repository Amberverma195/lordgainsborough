import * as SecureStore from 'expo-secure-store';

import { toAccountNamespace } from './accountNamespace';

export const SESSION_TOKEN_KEY = 'lgb.session.token';
export const LEGACY_TRACKING_PREFIX = 'lgb.track.';
export const GUEST_TRACKING_PREFIX = 'lgb.track.guest.';
export const USER_TRACKING_PREFIX = 'lgb.track.u.';

export function sanitizeIdentifier(value: string): string {
  return value.trim().replace(/[^A-Za-z0-9._-]/g, '_');
}

export function sanitizeEmail(email: string): string {
  return email
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]/g, '_');
}

export function getTrackingStorageKey(
  orderId: string,
  accountEmail?: string | null,
): string {
  const safeOrder = sanitizeIdentifier(orderId);
  const namespace = toAccountNamespace(accountEmail);
  if (namespace === 'guest') {
    return `lgb.track.guest.${safeOrder}.v2`;
  }
  return `lgb.track.u.${namespace}.${safeOrder}.v2`;
}

export function getTrackingIndexStorageKey(
  accountEmail?: string | null,
): string {
  const namespace = toAccountNamespace(accountEmail);
  if (namespace === 'guest') {
    return 'lgb.track.idx.guest.v2';
  }
  return `lgb.track.idx.u.${namespace}.v2`;
}

export function getLegacyTrackingStorageKey(orderId: string): string {
  const safeOrder = sanitizeIdentifier(orderId);
  return `lgb.track.${safeOrder}`;
}

export function getLegacyV1TrackingStorageKey(
  orderId: string,
  accountEmail?: string | null,
): string {
  const safeOrder = sanitizeIdentifier(orderId);
  if (!accountEmail || !accountEmail.trim()) {
    return `lgb.track.guest.${safeOrder}.v1`;
  }
  const safeEmail = sanitizeEmail(accountEmail);
  return `lgb.track.u.${safeEmail}.${safeOrder}.v1`;
}

export function getLegacyV1TrackingIndexStorageKey(
  accountEmail?: string | null,
): string {
  if (!accountEmail || !accountEmail.trim()) {
    return 'lgb.track.idx.guest.v1';
  }
  const safeEmail = sanitizeEmail(accountEmail);
  return `lgb.track.idx.u.${safeEmail}.v1`;
}

export async function readSessionToken(strict = false): Promise<string | null> {
  try {
    const token = await SecureStore.getItemAsync(SESSION_TOKEN_KEY);
    return token && token.trim() ? token.trim() : null;
  } catch (error) {
    if (strict) throw error;
    return null;
  }
}

export async function writeSessionToken(token: string): Promise<void> {
  await SecureStore.setItemAsync(SESSION_TOKEN_KEY, token.trim());
}

export async function clearSessionToken(): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(SESSION_TOKEN_KEY);
  } catch {
    // A missing key is already signed out.
  }
}

export async function purgeLegacyTrackingToken(
  orderId: string,
  accountEmail?: string | null,
): Promise<void> {
  if (!orderId || !orderId.trim()) {
    return;
  }
  try {
    await Promise.all([
      SecureStore.deleteItemAsync(getLegacyTrackingStorageKey(orderId)),
      SecureStore.deleteItemAsync(
        getLegacyV1TrackingStorageKey(orderId, accountEmail),
      ),
    ]);
  } catch {
    // Missing key or deletion failure is ignored
  }
}

async function readTrackingIndex(
  accountEmail?: string | null,
): Promise<string[]> {
  try {
    const raw = await SecureStore.getItemAsync(
      getTrackingIndexStorageKey(accountEmail),
    );
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed)
      ? parsed.filter((id) => typeof id === 'string')
      : [];
  } catch {
    return [];
  }
}

async function addToTrackingIndex(
  orderId: string,
  accountEmail?: string | null,
): Promise<void> {
  const raw = await SecureStore.getItemAsync(
    getTrackingIndexStorageKey(accountEmail),
  );
  const current: unknown = raw ? JSON.parse(raw) : [];
  if (!Array.isArray(current) || !current.every((id) => typeof id === 'string'))
    throw new Error('Tracking index is unavailable');
  if (!current.includes(orderId))
    await SecureStore.setItemAsync(
      getTrackingIndexStorageKey(accountEmail),
      JSON.stringify([...current, orderId]),
    );
}

async function removeFromTrackingIndex(
  orderId: string,
  accountEmail?: string | null,
): Promise<void> {
  try {
    const current = await readTrackingIndex(accountEmail);
    const updated = current.filter((id) => id !== orderId);
    if (updated.length > 0) {
      await SecureStore.setItemAsync(
        getTrackingIndexStorageKey(accountEmail),
        JSON.stringify(updated),
      );
    } else {
      await SecureStore.deleteItemAsync(
        getTrackingIndexStorageKey(accountEmail),
      );
    }
  } catch {
    // Non-fatal
  }
}

export async function writeTrackingToken(
  orderId: string,
  token: string,
  accountEmail?: string | null,
): Promise<void> {
  if (!orderId || !orderId.trim() || !token || !token.trim()) {
    return;
  }
  const key = getTrackingStorageKey(orderId, accountEmail);
  // Index first so every saved credential remains discoverable for deletion.
  await addToTrackingIndex(orderId.trim(), accountEmail);
  await SecureStore.setItemAsync(key, token.trim());

  // Purge legacy unisolated and v1 keys for this order if present to eliminate token leakage
  await purgeLegacyTrackingToken(orderId, accountEmail);
}

export async function readTrackingToken(
  orderId: string,
  accountEmail?: string | null,
): Promise<string | null> {
  if (!orderId || !orderId.trim()) {
    return null;
  }
  // When an account is provided, only read credentials explicitly scoped to that unambiguous account.
  // Never fall back to un-scoped legacy keys or keys from another account.
  const key = getTrackingStorageKey(orderId, accountEmail);
  try {
    const token = await SecureStore.getItemAsync(key);
    return token && token.trim() ? token.trim() : null;
  } catch {
    return null;
  }
}

export async function deleteTrackingToken(
  orderId: string,
  accountEmail?: string | null,
): Promise<void> {
  if (!orderId || !orderId.trim()) {
    return;
  }
  try {
    await Promise.all([
      SecureStore.deleteItemAsync(getTrackingStorageKey(orderId, accountEmail)),
      SecureStore.deleteItemAsync(getLegacyTrackingStorageKey(orderId)),
      SecureStore.deleteItemAsync(
        getLegacyV1TrackingStorageKey(orderId, accountEmail),
      ),
    ]);
  } catch {
    // Ignore cleanup failures
  }
  await removeFromTrackingIndex(orderId.trim(), accountEmail);
}

export async function clearAccountTrackingTokens(
  accountEmail?: string | null,
): Promise<void> {
  try {
    const orderIds = await readTrackingIndex(accountEmail);
    await Promise.all(
      orderIds.map(async (orderId) => {
        try {
          await Promise.all([
            SecureStore.deleteItemAsync(
              getTrackingStorageKey(orderId, accountEmail),
            ),
            SecureStore.deleteItemAsync(getLegacyTrackingStorageKey(orderId)),
            SecureStore.deleteItemAsync(
              getLegacyV1TrackingStorageKey(orderId, accountEmail),
            ),
          ]);
        } catch {
          // Ignore individual delete failures
        }
      }),
    );
    await Promise.all([
      SecureStore.deleteItemAsync(getTrackingIndexStorageKey(accountEmail)),
      SecureStore.deleteItemAsync(
        getLegacyV1TrackingIndexStorageKey(accountEmail),
      ),
    ]);
  } catch {
    // Ignore cleanup failures
  }
}
