import {
  clearAccountTrackingTokens,
  deleteTrackingToken,
  getLegacyTrackingStorageKey,
  getLegacyV1TrackingStorageKey,
  getTrackingIndexStorageKey,
  getTrackingStorageKey,
  purgeLegacyTrackingToken,
  readSessionToken,
  readTrackingToken,
  writeSessionToken,
  writeTrackingToken,
} from '../secureSession';
import { toAccountNamespace } from '../accountNamespace';

const mockStore = new Map<string, string>();

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async (key: string) => mockStore.get(key) ?? null),
  setItemAsync: jest.fn(async (key: string, value: string) => {
    mockStore.set(key, value);
  }),
  deleteItemAsync: jest.fn(async (key: string) => {
    mockStore.delete(key);
  }),
}));

describe('Order tracking credential isolation in SecureStore', () => {
  beforeEach(() => {
    mockStore.clear();
    jest.clearAllMocks();
  });

  describe('Storage key derivation and collision resistance', () => {
    it('scopes keys to the unambiguous account namespace and sanitized order id (v2)', () => {
      const nsA = toAccountNamespace('Customer.A@Example.COM');
      const key = getTrackingStorageKey('ord-100', 'Customer.A@Example.COM');
      expect(key).toBe(`lgb.track.u.${nsA}.ord-100.v2`);

      const nsUser = toAccountNamespace('User@domain.com');
      const specialKey = getTrackingStorageKey('ord/100 #1', 'User@domain.com');
      expect(specialKey).toBe(`lgb.track.u.${nsUser}.ord_100__1.v2`);
    });

    it('guarantees collision resistance between alice+promo@example.com and alice_promo@example.com', () => {
      const keyPlus = getTrackingStorageKey('order-1', 'alice+promo@example.com');
      const keyUnder = getTrackingStorageKey('order-1', 'alice_promo@example.com');
      expect(keyPlus).not.toBe(keyUnder);

      const idxPlus = getTrackingIndexStorageKey('alice+promo@example.com');
      const idxUnder = getTrackingIndexStorageKey('alice_promo@example.com');
      expect(idxPlus).not.toBe(idxUnder);
    });

    it('scopes unauthenticated/guest tokens to the guest namespace (v2)', () => {
      expect(getTrackingStorageKey('ord-100', null)).toBe('lgb.track.guest.ord-100.v2');
      expect(getTrackingStorageKey('ord-100', '')).toBe('lgb.track.guest.ord-100.v2');
      expect(getTrackingStorageKey('ord-100', undefined)).toBe('lgb.track.guest.ord-100.v2');
      expect(getTrackingIndexStorageKey(null)).toBe('lgb.track.idx.guest.v2');
    });

    it('generates legacy key formats for backward compatibility cleanup', () => {
      expect(getLegacyTrackingStorageKey('ord-100')).toBe('lgb.track.ord-100');
      expect(getLegacyV1TrackingStorageKey('ord-100', 'user@domain.com')).toBe('lgb.track.u.user_domain.com.ord-100.v1');
    });
  });

  describe('Account-switch isolation', () => {
    it('prevents a newly signed-in account from reading another account stored tracking token', async () => {
      // User A places an order
      await writeTrackingToken('order-aaa-1', 'secret-token-user-a', 'usera@example.com');

      // User B signs in and attempts to access order-aaa-1
      const userBToken = await readTrackingToken('order-aaa-1', 'userb@example.com');
      expect(userBToken).toBeNull();

      // User A can still access their own order tracking token
      const userAToken = await readTrackingToken('order-aaa-1', 'usera@example.com');
      expect(userAToken).toBe('secret-token-user-a');

      // User B places their own order
      await writeTrackingToken('order-bbb-1', 'secret-token-user-b', 'userb@example.com');

      // User A cannot access User B's order tracking token
      expect(await readTrackingToken('order-bbb-1', 'usera@example.com')).toBeNull();
      // User B can access their own token
      expect(await readTrackingToken('order-bbb-1', 'userb@example.com')).toBe('secret-token-user-b');
    });

    it('isolates credentials between alice+promo@example.com and alice_promo@example.com', async () => {
      await writeTrackingToken('ord-collide', 'token-plus', 'alice+promo@example.com');
      await writeTrackingToken('ord-collide', 'token-under', 'alice_promo@example.com');

      expect(await readTrackingToken('ord-collide', 'alice+promo@example.com')).toBe('token-plus');
      expect(await readTrackingToken('ord-collide', 'alice_promo@example.com')).toBe('token-under');
    });

    it('handles email case-insensitivity and whitespace during account lookup', async () => {
      await writeTrackingToken('ord-ci', 'token-ci', 'Customer.Smith@domain.com');

      expect(await readTrackingToken('ord-ci', '  customer.smith@domain.com  ')).toBe('token-ci');
      expect(await readTrackingToken('ord-ci', 'CUSTOMER.SMITH@DOMAIN.COM')).toBe('token-ci');
      expect(await readTrackingToken('ord-ci', 'other@domain.com')).toBeNull();
    });
  });

  describe('Logout isolation and cleanup', () => {
    it('prevents guest or unauthenticated callers from accessing account tokens after logout', async () => {
      await writeTrackingToken('order-priv-1', 'token-priv', 'usera@example.com');

      // An unauthenticated lookup (no email) must not access the user's token
      expect(await readTrackingToken('order-priv-1', null)).toBeNull();
      expect(await readTrackingToken('order-priv-1', undefined)).toBeNull();
      expect(await readTrackingToken('order-priv-1')).toBeNull();
    });

    it('clears all account tracking tokens and index on explicit account cleanup (logout)', async () => {
      await writeTrackingToken('order-1', 'token-1', 'usera@example.com');
      await writeTrackingToken('order-2', 'token-2', 'usera@example.com');

      const key1 = getTrackingStorageKey('order-1', 'usera@example.com');
      const key2 = getTrackingStorageKey('order-2', 'usera@example.com');
      const idxKey = getTrackingIndexStorageKey('usera@example.com');

      expect(mockStore.has(key1)).toBe(true);
      expect(mockStore.has(key2)).toBe(true);
      expect(mockStore.has(idxKey)).toBe(true);

      // Perform account logout cleanup
      await clearAccountTrackingTokens('usera@example.com');

      expect(mockStore.has(key1)).toBe(false);
      expect(mockStore.has(key2)).toBe(false);
      expect(mockStore.has(idxKey)).toBe(false);

      expect(await readTrackingToken('order-1', 'usera@example.com')).toBeNull();
      expect(await readTrackingToken('order-2', 'usera@example.com')).toBeNull();
    });
  });

  describe('App-restart authorized order recovery', () => {
    it('preserves tracking tokens across app restart when session is restored', async () => {
      // User A submits order and writes session & tracking token
      await writeSessionToken('jwt-session-usera');
      await writeTrackingToken('order-restart-1', 'recovery-token-xyz', 'usera@example.com');

      // Simulate app restart: SecureStore retains encrypted entries
      // App restores session token:
      const restoredSession = await readSessionToken();
      expect(restoredSession).toBe('jwt-session-usera');

      // With restored session for User A, read tracking token:
      const recoveredToken = await readTrackingToken('order-restart-1', 'usera@example.com');
      expect(recoveredToken).toBe('recovery-token-xyz');
    });
  });

  describe('Legacy un-scoped and v1 key isolation and safe non-migration', () => {
    it('never returns un-scoped legacy tokens or v1 collision-prone tokens to signed-in accounts', async () => {
      // Legacy device had global un-scoped key from prior mobile version
      const legacyKey = getLegacyTrackingStorageKey('order-legacy-1');
      mockStore.set(legacyKey, 'old-unscoped-token');

      // Legacy device also had v1 collision-prone key
      const legacyV1Key = getLegacyV1TrackingStorageKey('order-legacy-1', 'alice_promo@example.com');
      mockStore.set(legacyV1Key, 'old-v1-colliding-token');

      // User B signs in and views order-legacy-1: must NOT return legacy or v1 token
      const tokenB = await readTrackingToken('order-legacy-1', 'userb@example.com');
      expect(tokenB).toBeNull();

      // Alice+promo views order-legacy-1: must NOT adopt or migrate v1 token
      const tokenAlicePlus = await readTrackingToken('order-legacy-1', 'alice+promo@example.com');
      expect(tokenAlicePlus).toBeNull();
    });

    it('purges un-scoped legacy key and v1 key when writing a new scoped token for the order', async () => {
      const legacyKey = getLegacyTrackingStorageKey('order-migrated');
      const legacyV1Key = getLegacyV1TrackingStorageKey('order-migrated', 'userb@example.com');
      mockStore.set(legacyKey, 'old-leaked-token');
      mockStore.set(legacyV1Key, 'old-v1-token');

      await writeTrackingToken('order-migrated', 'new-scoped-token', 'userb@example.com');

      expect(mockStore.get(legacyKey)).toBeUndefined();
      expect(mockStore.get(legacyV1Key)).toBeUndefined();
      expect(await readTrackingToken('order-migrated', 'userb@example.com')).toBe('new-scoped-token');
    });

    it('purgeLegacyTrackingToken deletes the legacy keys directly', async () => {
      const legacyKey = getLegacyTrackingStorageKey('order-purge');
      const legacyV1Key = getLegacyV1TrackingStorageKey('order-purge', 'user@domain.com');
      mockStore.set(legacyKey, 'legacy-val');
      mockStore.set(legacyV1Key, 'v1-val');

      await purgeLegacyTrackingToken('order-purge', 'user@domain.com');
      expect(mockStore.has(legacyKey)).toBe(false);
      expect(mockStore.has(legacyV1Key)).toBe(false);
    });
  });

  describe('Individual token deletion', () => {
    it('deletes specific token and updates the index', async () => {
      await writeTrackingToken('ord-del-1', 'tok-1', 'usera@example.com');
      await writeTrackingToken('ord-del-2', 'tok-2', 'usera@example.com');

      await deleteTrackingToken('ord-del-1', 'usera@example.com');

      expect(await readTrackingToken('ord-del-1', 'usera@example.com')).toBeNull();
      expect(await readTrackingToken('ord-del-2', 'usera@example.com')).toBe('tok-2');
    });
  });
});
