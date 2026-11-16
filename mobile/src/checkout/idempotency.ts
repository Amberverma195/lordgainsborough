export interface KeyValueStore {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

export const CHECKOUT_IDEMPOTENCY_KEY = 'lgb.checkout.idempotencyKey';
export const CHECKOUT_FINGERPRINT_KEY = 'lgb.checkout.fingerprint';

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
): Promise<string> {
  const [savedKey, savedFingerprint] = await Promise.all([
    store.getItem(CHECKOUT_IDEMPOTENCY_KEY),
    store.getItem(CHECKOUT_FINGERPRINT_KEY),
  ]);
  if (savedKey && savedKey.length <= 128 && savedFingerprint === fingerprint) {
    return savedKey;
  }
  const key = createIdempotencyKey(randomUUID);
  await store.setItem(CHECKOUT_IDEMPOTENCY_KEY, key);
  await store.setItem(CHECKOUT_FINGERPRINT_KEY, fingerprint);
  return key;
}

export async function clearCheckoutIdempotencyKey(store: KeyValueStore): Promise<void> {
  await store.removeItem(CHECKOUT_IDEMPOTENCY_KEY);
  await store.removeItem(CHECKOUT_FINGERPRINT_KEY);
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

export function memoryStore(seed: Record<string, string> = {}): KeyValueStore & { snapshot: () => Record<string, string> } {
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
    snapshot() {
      return Object.fromEntries(values.entries());
    },
  };
}
