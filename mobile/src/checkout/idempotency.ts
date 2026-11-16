export interface KeyValueStore {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
  getAllKeys?(): Promise<readonly string[]>;
}

export const CHECKOUT_IDEMPOTENCY_KEY = 'lgb.checkout.idempotencyKey';
export const CHECKOUT_FINGERPRINT_KEY = 'lgb.checkout.fingerprint';
export const LEGACY_CHECKOUT_IDEMPOTENCY_KEY = CHECKOUT_IDEMPOTENCY_KEY;
export const LEGACY_CHECKOUT_FINGERPRINT_KEY = CHECKOUT_FINGERPRINT_KEY;
export const GUEST_CHECKOUT_IDEMPOTENCY_KEY = 'lgb.checkout.guest.idempotencyKey.v1';
export const GUEST_CHECKOUT_FINGERPRINT_KEY = 'lgb.checkout.guest.fingerprint.v1';

export function getCheckoutIdempotencyStorageKey(accountEmail?: string | null): string {
  if (!accountEmail || !accountEmail.trim()) {
    return GUEST_CHECKOUT_IDEMPOTENCY_KEY;
  }
  const normalized = accountEmail.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
  return `lgb.checkout.u.${normalized}.idempotencyKey.v1`;
}

export function getCheckoutFingerprintStorageKey(accountEmail?: string | null): string {
  if (!accountEmail || !accountEmail.trim()) {
    return GUEST_CHECKOUT_FINGERPRINT_KEY;
  }
  const normalized = accountEmail.trim().toLowerCase().replace(/[^a-z0-9._-]/g, '_');
  return `lgb.checkout.u.${normalized}.fingerprint.v1`;
}

export interface FingerprintInput {
  lines: { menuItemId: string; quantity: number }[];
  pickupDate: string;
  pickupTime: string;
  specialInstructions: string;
}

export function checkoutFingerprint(input: FingerprintInput): string {
  const lines = [...input.lines]
    .map((line) => `${line.menuItemId}:${line.quantity}`)
    .sort()
    .join('|');
  const instructions = input.specialInstructions.trim().slice(0, 500);
  return `${lines}::${input.pickupDate.trim()}::${input.pickupTime.trim()}::${instructions}`;
}

export function createIdempotencyKey(randomUUID: () => string = () => crypto.randomUUID()): string {
  const key = randomUUID();
  return key.length <= 128 ? key : key.slice(0, 128);
}

export async function getOrCreateCheckoutIdempotencyKey(
  store: KeyValueStore,
  fingerprint: string,
  randomUUID?: () => string,
  accountEmail?: string | null,
): Promise<string> {
  const idempotencyKeyName = getCheckoutIdempotencyStorageKey(accountEmail);
  const fingerprintKeyName = getCheckoutFingerprintStorageKey(accountEmail);

  // Clean up legacy global keys if present to prevent cross-account leakage
  try {
    await Promise.all([
      store.removeItem(LEGACY_CHECKOUT_IDEMPOTENCY_KEY),
      store.removeItem(LEGACY_CHECKOUT_FINGERPRINT_KEY),
    ]);
  } catch {
    // Ignore cleanup errors
  }

  const [savedKey, savedFingerprint] = await Promise.all([
    store.getItem(idempotencyKeyName),
    store.getItem(fingerprintKeyName),
  ]);
  if (savedKey && savedKey.length <= 128 && savedFingerprint === fingerprint) {
    return savedKey;
  }
  const key = createIdempotencyKey(randomUUID);
  await store.setItem(idempotencyKeyName, key);
  await store.setItem(fingerprintKeyName, fingerprint);
  return key;
}

export async function clearCheckoutIdempotencyKey(
  store: KeyValueStore,
  accountEmail?: string | null,
): Promise<void> {
  const idempotencyKeyName = getCheckoutIdempotencyStorageKey(accountEmail);
  const fingerprintKeyName = getCheckoutFingerprintStorageKey(accountEmail);
  await Promise.all([
    store.removeItem(idempotencyKeyName),
    store.removeItem(fingerprintKeyName),
    store.removeItem(LEGACY_CHECKOUT_IDEMPOTENCY_KEY),
    store.removeItem(LEGACY_CHECKOUT_FINGERPRINT_KEY),
  ]);
}

export async function clearAllCheckoutIdempotencyKeys(store: KeyValueStore): Promise<void> {
  if (typeof store.getAllKeys === 'function') {
    try {
      const allKeys = await store.getAllKeys();
      const checkoutKeys = allKeys.filter((k) => k.startsWith('lgb.checkout.'));
      await Promise.all(checkoutKeys.map((k) => store.removeItem(k)));
      return;
    } catch {
      // Fall back to known keys
    }
  }
  await Promise.all([
    store.removeItem(LEGACY_CHECKOUT_IDEMPOTENCY_KEY),
    store.removeItem(LEGACY_CHECKOUT_FINGERPRINT_KEY),
    store.removeItem(GUEST_CHECKOUT_IDEMPOTENCY_KEY),
    store.removeItem(GUEST_CHECKOUT_FINGERPRINT_KEY),
  ]);
}

export function createSubmitGuard(): { begin: () => boolean; end: () => void } {
  let active = false;
  return {
    begin() {
      if (active) {
        return false;
      }
      active = true;
      return true;
    },
    end() {
      active = false;
    },
  };
}

export function memoryStore(seed: Record<string, string> = {}): KeyValueStore & {
  snapshot: () => Record<string, string>;
  getAllKeys: () => Promise<string[]>;
} {
  const values = new Map(Object.entries(seed));
  return {
    async getItem(key) {
      return values.get(key) ?? null;
    },
    async setItem(key, value) {
      values.set(key, value);
    },
    async removeItem(key) {
      values.delete(key);
    },
    async getAllKeys() {
      return Array.from(values.keys());
    },
    snapshot() {
      return Object.fromEntries(values.entries());
    },
  };
}
