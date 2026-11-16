import * as SecureStore from 'expo-secure-store';

const SESSION_TOKEN_KEY = 'lgb.session.token';

function trackingKey(orderId: string): string {
  const safe = orderId.replace(/[^A-Za-z0-9._-]/g, '_');
  return `lgb.track.${safe}`;
}

export async function readSessionToken(): Promise<string | null> {
  try {
    const token = await SecureStore.getItemAsync(SESSION_TOKEN_KEY);
    return token && token.trim() ? token : null;
  } catch {
    return null;
  }
}

export async function writeSessionToken(token: string): Promise<void> {
  await SecureStore.setItemAsync(SESSION_TOKEN_KEY, token);
}

export async function clearSessionToken(): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(SESSION_TOKEN_KEY);
  } catch {
    // A missing key is already signed out.
  }
}

export async function writeTrackingToken(orderId: string, token: string): Promise<void> {
  if (!orderId || !token) {
    return;
  }
  await SecureStore.setItemAsync(trackingKey(orderId), token);
}

export async function readTrackingToken(orderId: string): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(trackingKey(orderId));
  } catch {
    return null;
  }
}
