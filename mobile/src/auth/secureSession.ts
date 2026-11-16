import * as SecureStore from 'expo-secure-store';

export const SESSION_TOKEN_KEY = 'lgb.session.token';
export const LEGACY_TRACKING_PREFIX = 'lgb.track.';
export const GUEST_TRACKING_PREFIX = 'lgb.track.guest.';
export const USER_TRACKING_PREFIX = 'lgb.track.u.';

export function sanitizeIdentifier(value: string): string {
  return value.trim().replace(/[^A-Za-z0-9._-]/g, '_');
}

export function sanitizeEmail(email: string): string {
  return email.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
}

export function getTrackingStorageKey(orderId: string, accountEmail?: string | null): string {
  const safeOrder = sanitizeIdentifier(orderId);
  if (!accountEmail || !accountEmail.trim()) {
    return `lgb.track.guest.${safeOrder}.v1`;
  }
  const safeEmail = sanitizeEmail(accountEmail);
  return `lgb.track.u.${safeEmail}.${safeOrder}.v1`;
}

export function getTrackingIndexStorageKey(accountEmail?: string | null): string {
  if (!accountEmail || !accountEmail.trim()) {
    return 'lgb.track.idx.guest.v1';
  }
  const safeEmail = sanitizeEmail(accountEmail);
  return `lgb.track.idx.u.${safeEmail}.v1`;
}

export function getLegacyTrackingStorageKey(orderId: string): string {
  const safeOrder = sanitizeIdentifier(orderId);
  return `lgb.track.${safeOrder}`;
}

export async function readSessionToken(): Promise<string | null> {
  try {
    const token = await SecureStore.getItemAsync(SESSION_TOKEN_KEY);
    return token && token.trim() ? token.trim() : null;
  } catch {
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

export async function purgeLegacyTrackingToken(orderId: string): Promise<void> {
  if (!orderId || !orderId.trim()) {
    return;
  }
  try {
    await SecureStore.deleteItemAsync(getLegacyTrackingStorageKey(orderId));
  } catch {
    // Missing key or deletion failure is ignored
  }
}

async function readTrackingIndex(accountEmail?: string | null): Promise<string[]> {
  try {
    const raw = await SecureStore.getItemAsync(getTrackingIndexStorageKey(accountEmail));
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((id) => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

async function addToTrackingIndex(orderId: string, accountEmail?: string | null): Promise<void> {
  try {
    const current = await readTrackingIndex(accountEmail);
    if (!current.includes(orderId)) {
      current.push(orderId);
      await SecureStore.setItemAsync(
        getTrackingIndexStorageKey(accountEmail),
        JSON.stringify(current),
      );
    }
  } catch {
    // Non-fatal if index update fails
  }
}

async function removeFromTrackingIndex(orderId: string, accountEmail?: string | null): Promise<void> {
  try {
    const current = await readTrackingIndex(accountEmail);
    const updated = current.filter((id) => id !== orderId);
    if (updated.length > 0) {
      await SecureStore.setItemAsync(
        getTrackingIndexStorageKey(accountEmail),
        JSON.stringify(updated),
      );
    } else {
      await SecureStore.deleteItemAsync(getTrackingIndexStorageKey(accountEmail));
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
  await SecureStore.setItemAsync(key, token.trim());
  await addToTrackingIndex(orderId.trim(), accountEmail);

  // Purge legacy unisolated key for this order if present to eliminate un-scoped token leakage
  await purgeLegacyTrackingToken(orderId);
}

export async function readTrackingToken(
  orderId: string,
  accountEmail?: string | null,
): Promise<string | null> {
  if (!orderId || !orderId.trim()) {
    return null;
  }
  // When an account is provided, only read credentials explicitly scoped to that account.
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
    ]);
  } catch {
    // Ignore cleanup failures
  }
  await removeFromTrackingIndex(orderId.trim(), accountEmail);
}

export async function clearAccountTrackingTokens(accountEmail?: string | null): Promise<void> {
  try {
    const orderIds = await readTrackingIndex(accountEmail);
    await Promise.all(
      orderIds.map(async (orderId) => {
        try {
          await SecureStore.deleteItemAsync(getTrackingStorageKey(orderId, accountEmail));
          await SecureStore.deleteItemAsync(getLegacyTrackingStorageKey(orderId));
        } catch {
          // Ignore individual delete failures
        }
      }),
    );
    await SecureStore.deleteItemAsync(getTrackingIndexStorageKey(accountEmail));
  } catch {
    // Ignore cleanup failures
  }
}
