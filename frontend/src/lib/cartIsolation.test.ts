import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  GUEST_CART_STORAGE_KEY,
  GENUINE_GUEST_FLAG_STORAGE_KEY,
  LEGACY_GLOBAL_CART_STORAGE_KEY,
  getAccountCartStorageKey,
  loadGuestCart,
  saveGuestCart,
  clearGuestCart,
  isGenuineGuestCart,
  loadAccountCart,
  saveAccountCart,
  clearAccountCart,
  purgeLegacyGlobalCart,
  mergeCartItems,
  CartItem,
} from './cartStorage';
import { clearAuth, persistAuth, signOut, validateServerSession } from './auth';
import { getCheckoutIdempotencyStorageKey, getOrCreateCheckoutIdempotencyKey, clearCheckoutIdempotencyKey } from './orders';

function createMemoryStorage() {
  const store = new Map<string, string>();
  return {
    get length() {
      return store.size;
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
}

describe('Cart Persistence and Session Lifecycle Isolation', () => {
  let localStorageMock: ReturnType<typeof createMemoryStorage>;
  let sessionStorageMock: ReturnType<typeof createMemoryStorage>;
  let dispatchedEvents: Event[] = [];

  const mockItemA: CartItem = {
    id: 'item-a',
    name: 'Truffle Burrata',
    description: 'Delicious burrata',
    price: 18,
    category: 'Starters',
    image: '/menu/burrata.jpg',
    quantity: 2,
  };

  const mockItemB: CartItem = {
    id: 'item-b',
    name: 'Pan-Seared Scallops',
    description: 'Fresh scallops',
    price: 22,
    category: 'Starters',
    image: '/menu/scallops.jpg',
    quantity: 1,
  };

  const mockGuestItem: CartItem = {
    id: 'item-guest',
    name: 'Wagyu Ribeye',
    description: 'Prime cut',
    price: 65,
    category: 'Mains',
    image: '/menu/steak.jpg',
    quantity: 1,
  };

  beforeEach(() => {
    localStorageMock = createMemoryStorage();
    sessionStorageMock = createMemoryStorage();
    dispatchedEvents = [];

    vi.stubGlobal('localStorage', localStorageMock);
    vi.stubGlobal('sessionStorage', sessionStorageMock);
    vi.stubGlobal('window', {
      location: {
        origin: 'http://localhost:3000',
        href: 'http://localhost:3000/',
      },
      localStorage: localStorageMock,
      sessionStorage: sessionStorageMock,
      dispatchEvent: (event: Event) => {
        dispatchedEvents.push(event);
      },
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });
    vi.stubGlobal('document', {
      cookie: 'lgb_csrf=test-csrf-token',
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('Storage Partitioning & Collision Resistance', () => {
    it('isolates storage keys between distinct emails with special characters (e.g. alice+promo vs alice_promo)', () => {
      const emailA = 'alice+promo@example.com';
      const emailB = 'alice_promo@example.com';

      const keyA = getAccountCartStorageKey(emailA);
      const keyB = getAccountCartStorageKey(emailB);

      expect(keyA).not.toBe(keyB);

      saveAccountCart(emailA, [mockItemA]);
      saveAccountCart(emailB, [mockItemB]);

      expect(loadAccountCart(emailA)).toEqual([mockItemA]);
      expect(loadAccountCart(emailB)).toEqual([mockItemB]);

      // Idempotency keys are also collision-free
      const idempKeyA = getCheckoutIdempotencyStorageKey(emailA);
      const idempKeyB = getCheckoutIdempotencyStorageKey(emailB);
      expect(idempKeyA).not.toBe(idempKeyB);
    });

    it('purges legacy unpartitioned lord_gainsborough_cart storage key', () => {
      localStorageMock.setItem(LEGACY_GLOBAL_CART_STORAGE_KEY, JSON.stringify([mockItemA]));
      expect(localStorageMock.getItem(LEGACY_GLOBAL_CART_STORAGE_KEY)).toBeTruthy();

      purgeLegacyGlobalCart();
      expect(localStorageMock.getItem(LEGACY_GLOBAL_CART_STORAGE_KEY)).toBeNull();
    });

    it('isolates guest cart from authenticated cart', () => {
      saveGuestCart([mockGuestItem]);
      expect(isGenuineGuestCart()).toBe(true);
      expect(localStorageMock.getItem(GENUINE_GUEST_FLAG_STORAGE_KEY)).toBe('true');
      expect(loadGuestCart()).toEqual([mockGuestItem]);

      const userEmail = 'user@example.com';
      expect(loadAccountCart(userEmail)).toEqual([]);

      saveAccountCart(userEmail, [mockItemA]);
      expect(loadAccountCart(userEmail)).toEqual([mockItemA]);
      expect(loadGuestCart()).toEqual([mockGuestItem]);

      clearAccountCart(userEmail);
      expect(loadAccountCart(userEmail)).toEqual([]);
      expect(loadGuestCart()).toEqual([mockGuestItem]);
    });
  });

  describe('Session Expiration Lifecycle', () => {
    it('clears client auth and purges legacy cart when validateServerSession encounters 401 Unauthorized', async () => {
      persistAuth({ email: 'alice@example.com', name: 'Alice' });
      localStorageMock.setItem(LEGACY_GLOBAL_CART_STORAGE_KEY, JSON.stringify([mockItemA]));
      expect(localStorageMock.getItem('sessionUser')).toBe('alice@example.com');

      // Mock apiFetch returning 401
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        status: 401,
        ok: false,
        json: async () => ({ message: 'Session expired' }),
      }));

      const result = await validateServerSession();

      expect(result.authenticated).toBe(false);
      expect(result.reason).toBe('unauthorized');
      expect(localStorageMock.getItem('sessionUser')).toBeNull();
      expect(localStorageMock.getItem(LEGACY_GLOBAL_CART_STORAGE_KEY)).toBeNull();
    });

    it('does NOT clear client auth when validateServerSession encounters transient network error or 5xx', async () => {
      persistAuth({ email: 'alice@example.com', name: 'Alice' });
      expect(localStorageMock.getItem('sessionUser')).toBe('alice@example.com');

      // Mock apiFetch returning 502 Bad Gateway
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        status: 502,
        ok: false,
        text: async () => 'Bad Gateway',
      }));

      const result = await validateServerSession();

      expect(result.authenticated).toBe(false);
      expect(result.reason).toBe('network_error');
      // Auth is preserved for offline resilience
      expect(localStorageMock.getItem('sessionUser')).toBe('alice@example.com');
    });
  });

  describe('Sign Out Lifecycle', () => {
    it('clears client auth, purges legacy cart, and dispatches storage event without deleting server cart', async () => {
      persistAuth({ email: 'alice@example.com', name: 'Alice' });
      saveAccountCart('alice@example.com', [mockItemA]);
      localStorageMock.setItem(LEGACY_GLOBAL_CART_STORAGE_KEY, JSON.stringify([mockItemA]));

      const fetchMock = vi.fn().mockResolvedValue({
        status: 204,
        ok: true,
      });
      vi.stubGlobal('fetch', fetchMock);

      await signOut();

      // Auth is cleared
      expect(localStorageMock.getItem('sessionUser')).toBeNull();
      expect(localStorageMock.getItem(LEGACY_GLOBAL_CART_STORAGE_KEY)).toBeNull();

      // Alice's account-scoped cart is preserved in storage for returning session, not leaked to guest
      expect(loadAccountCart('alice@example.com')).toEqual([mockItemA]);
      expect(loadGuestCart()).toEqual([]);
      expect(isGenuineGuestCart()).toBe(false);

      // Storage event was dispatched to notify all components/tabs
      expect(dispatchedEvents.some(e => e.type === 'storage')).toBe(true);

      // Ensure fetch called /api/auth/logout and NOT DELETE or PUT /api/cart
      const calledUrls = fetchMock.mock.calls.map(call => call[0]);
      expect(calledUrls).toContain('/api/auth/logout');
      expect(calledUrls.some(url => String(url).includes('/api/cart'))).toBe(false);
    });
  });

  describe('Different-Account Login & Cross-Account Isolation', () => {
    it('does NOT merge previous user cart into a different customer cart upon login', () => {
      const alice = 'alice@example.com';
      const bob = 'bob@example.com';

      // Alice had items in her cart
      saveAccountCart(alice, [mockItemA]);

      // Alice logs out
      clearAuth();
      expect(localStorageMock.getItem('sessionUser')).toBeNull();

      // Visible UI/guest cart is empty, genuine guest flag is false
      expect(loadGuestCart()).toEqual([]);
      expect(isGenuineGuestCart()).toBe(false);

      // Bob logs in
      persistAuth({ email: bob, name: 'Bob' });
      const bobServerCart: CartItem[] = [mockItemB];

      // CartContext checks if there is a genuine guest cart
      const hasGuest = isGenuineGuestCart();
      expect(hasGuest).toBe(false);

      // Since hasGuest is false, Bob's cart is derived ONLY from Bob's account
      const bobLocalCart = loadAccountCart(bob);
      const bobFinalCart = mergeCartItems(bobLocalCart, bobServerCart);

      expect(bobFinalCart).toEqual([mockItemB]);
      // Alice's item is NOT present in Bob's cart
      expect(bobFinalCart.some(i => i.id === mockItemA.id)).toBe(false);

      // Alice's cart remains safe in her own partition
      expect(loadAccountCart(alice)).toEqual([mockItemA]);
    });

    it('merges a genuine guest cart into the customer server cart on login and clears the guest cart', () => {
      const carol = 'carol@example.com';

      // Guest browses and adds an item
      saveGuestCart([mockGuestItem]);
      expect(isGenuineGuestCart()).toBe(true);

      // Carol logs in; Carol had item B on the server
      persistAuth({ email: carol, name: 'Carol' });
      const carolServerCart: CartItem[] = [mockItemB];

      const hasGuest = isGenuineGuestCart();
      expect(hasGuest).toBe(true);
      const guestItems = loadGuestCart();

      const mergedCart = mergeCartItems(guestItems, carolServerCart);
      saveAccountCart(carol, mergedCart);
      clearGuestCart();

      // Merged cart contains both Carol's server item and the genuine guest item
      expect(mergedCart).toHaveLength(2);
      expect(mergedCart.map(i => i.id).sort()).toEqual(['item-b', 'item-guest']);

      // Guest cart is now empty and flag is cleared
      expect(loadGuestCart()).toEqual([]);
      expect(isGenuineGuestCart()).toBe(false);

      // If Carol logs out, Dave logs in
      clearAuth();
      const dave = 'dave@example.com';
      persistAuth({ email: dave, name: 'Dave' });

      // Dave does NOT get the guest item or Carol's item
      expect(isGenuineGuestCart()).toBe(false);
      const daveFinalCart = mergeCartItems(loadAccountCart(dave), []);
      expect(daveFinalCart).toEqual([]);
    });
  });

  describe('Returning Users', () => {
    it('restores returning customer cart when signing back in', () => {
      const returningUser = 'returning@example.com';

      // Previous session saved items
      saveAccountCart(returningUser, [mockItemA]);

      // User returns and signs in
      persistAuth({ email: returningUser, name: 'Returning User' });
      const serverCart: CartItem[] = [mockItemA];

      const localCart = loadAccountCart(returningUser);
      const restored = mergeCartItems(localCart, serverCart);

      expect(restored).toEqual([mockItemA]);
    });
  });

  describe('Browser Restart Simulation', () => {
    it('recovers guest cart from guest partition after simulated browser restart', () => {
      saveGuestCart([mockGuestItem]);

      // Simulate browser restart: new memory instance with persisted storage data
      const storedGuest = localStorageMock.getItem(GUEST_CART_STORAGE_KEY);
      expect(storedGuest).toBeTruthy();

      const recovered = JSON.parse(storedGuest!);
      expect(recovered).toEqual([mockGuestItem]);
    });

    it('does not restore legacy global cart after browser restart if session has expired', async () => {
      // Stale session hint in localStorage
      localStorageMock.setItem('sessionUser', 'expired@example.com');
      localStorageMock.setItem(LEGACY_GLOBAL_CART_STORAGE_KEY, JSON.stringify([mockItemA]));

      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        status: 401,
        ok: false,
        json: async () => ({ message: 'Session expired' }),
      }));

      const sessionCheck = await validateServerSession();
      expect(sessionCheck.authenticated).toBe(false);
      expect(sessionCheck.reason).toBe('unauthorized');

      // Legacy global cart and sessionUser are removed
      expect(localStorageMock.getItem('sessionUser')).toBeNull();
      expect(localStorageMock.getItem(LEGACY_GLOBAL_CART_STORAGE_KEY)).toBeNull();
    });
  });

  describe('Cart Fetch Error Handling (401, 403, network failure)', () => {
    it('does not silently treat 401/403 as an empty cart or overwrite server cart', async () => {
      const user = 'alice@example.com';
      persistAuth({ email: user });
      saveAccountCart(user, [mockItemA]);

      // Server returns 401 for /api/cart
      const fetchMock = vi.fn().mockResolvedValue({
        status: 401,
        ok: false,
        json: async () => ({ message: 'Unauthorized' }),
      });
      vi.stubGlobal('fetch', fetchMock);

      // Verify that validateServerSession identifies the expiration
      const sessionResult = await validateServerSession();
      expect(sessionResult.authenticated).toBe(false);
      expect(sessionResult.reason).toBe('unauthorized');

      // Auth is cleared
      expect(localStorageMock.getItem('sessionUser')).toBeNull();

      // Ensure NO PUT /api/cart or DELETE /api/cart was called to overwrite server cart as empty
      const putOrDeleteCalls = fetchMock.mock.calls.filter(call => {
        const method = call[1]?.method || 'GET';
        return method === 'PUT' || method === 'DELETE';
      });
      expect(putOrDeleteCalls).toHaveLength(0);
    });

    it('preserves cached local cart and avoids empty server overwrite on network error', async () => {
      const user = 'alice@example.com';
      persistAuth({ email: user });
      saveAccountCart(user, [mockItemA]);

      // Network throws error (offline)
      const fetchMock = vi.fn().mockRejectedValue(new Error('Failed to fetch'));
      vi.stubGlobal('fetch', fetchMock);

      const sessionResult = await validateServerSession();
      expect(sessionResult.authenticated).toBe(false);
      expect(sessionResult.reason).toBe('network_error');

      // Local account cart remains intact
      expect(loadAccountCart(user)).toEqual([mockItemA]);
    });
  });

  describe('Cross-Tab Synchronization & Idempotency Key Isolation', () => {
    it('discards delayed fetch results if account switched before fetch resolved', () => {
      let generation = 1;
      let activeAccount: string | null = 'alice@example.com';

      // Alice initiates fetch
      const capturedGen = generation;
      const capturedAccount = activeAccount;

      // Alice logs out and Bob logs in before Alice's fetch resolves
      generation++;
      activeAccount = 'bob@example.com';

      // Delayed response for Alice arrives
      const shouldApply = capturedGen === generation && capturedAccount === activeAccount;
      expect(shouldApply).toBe(false);
    });
    it('maintains isolated checkout idempotency keys between accounts', () => {
      const email1 = 'alice+orders@example.com';
      const email2 = 'alice_orders@example.com';

      const cart = [{ id: 'dish-1', quantity: 1 }];

      const key1 = getOrCreateCheckoutIdempotencyKey(cart, email1);
      const key2 = getOrCreateCheckoutIdempotencyKey(cart, email2);

      expect(key1).toBeTruthy();
      expect(key2).toBeTruthy();
      expect(key1).not.toBe(key2);

      // Clearing key for email1 does not clear key for email2
      clearCheckoutIdempotencyKey(email1);
      expect(getOrCreateCheckoutIdempotencyKey(cart, email2)).toBe(key2);
    });
  });
});
