import AsyncStorage from '@react-native-async-storage/async-storage';

import {
  CachedTaxConfig,
  clearCachedTaxConfig,
  DEFAULT_ESTIMATED_HST_RATE,
  ESTIMATED_HST_FALLBACK_NOTICE,
  formatTaxLabel,
  isValidTaxCache,
  isValidTaxRate,
  loadCachedTaxConfig,
  resolveCartTax,
  saveCachedTaxConfig,
  TAX_CACHE_MAX_AGE_MS,
  TAX_CONFIG_CACHE_KEY,
} from '../tax';

jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);

describe('Tax configuration validation, cache freshness, and resolution', () => {
  const baseNow = 1700000000000;

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();
  });

  describe('isValidTaxRate', () => {
    it('accepts valid non-negative rates between 0 and 1', () => {
      expect(isValidTaxRate(0)).toBe(true);
      expect(isValidTaxRate(0.13)).toBe(true);
      expect(isValidTaxRate(0.05)).toBe(true);
      expect(isValidTaxRate(1)).toBe(true);
    });

    it('rejects invalid, negative, NaN, non-finite, or excessive values', () => {
      expect(isValidTaxRate(-0.01)).toBe(false);
      expect(isValidTaxRate(1.05)).toBe(false);
      expect(isValidTaxRate(NaN)).toBe(false);
      expect(isValidTaxRate(Infinity)).toBe(false);
      expect(isValidTaxRate(null)).toBe(false);
      expect(isValidTaxRate(undefined)).toBe(false);
      expect(isValidTaxRate('0.13')).toBe(false);
    });
  });

  describe('isValidTaxCache and freshness rules', () => {
    it('accepts a cached entry verified within the 24-hour freshness window', () => {
      const entry: CachedTaxConfig = {
        taxRate: 0.13,
        verifiedAt: baseNow - 1000 * 60 * 60, // 1 hour ago
      };
      expect(isValidTaxCache(entry, baseNow)).toBe(true);
    });

    it('accepts a cache entry at the boundary of 24 hours', () => {
      const entry: CachedTaxConfig = {
        taxRate: 0.13,
        verifiedAt: baseNow - TAX_CACHE_MAX_AGE_MS,
      };
      expect(isValidTaxCache(entry, baseNow)).toBe(true);
    });

    it('rejects an expired cache entry older than 24 hours', () => {
      const entry: CachedTaxConfig = {
        taxRate: 0.13,
        verifiedAt: baseNow - TAX_CACHE_MAX_AGE_MS - 1000,
      };
      expect(isValidTaxCache(entry, baseNow)).toBe(false);
    });

    it('rejects future timestamps or non-positive timestamps', () => {
      expect(isValidTaxCache({ taxRate: 0.13, verifiedAt: baseNow + 1000 }, baseNow)).toBe(false);
      expect(isValidTaxCache({ taxRate: 0.13, verifiedAt: 0 }, baseNow)).toBe(false);
      expect(isValidTaxCache({ taxRate: 0.13, verifiedAt: -100 }, baseNow)).toBe(false);
    });

    it('rejects malformed or corrupted cache objects', () => {
      expect(isValidTaxCache(null, baseNow)).toBe(false);
      expect(isValidTaxCache('not-an-object', baseNow)).toBe(false);
      expect(isValidTaxCache({ taxRate: -0.1, verifiedAt: baseNow }, baseNow)).toBe(false);
      expect(isValidTaxCache({ verifiedAt: baseNow }, baseNow)).toBe(false);
      expect(isValidTaxCache({ taxRate: 0.13 }, baseNow)).toBe(false);
    });
  });

  describe('formatTaxLabel', () => {
    it('formats fallback label as Estimated HST (13%)', () => {
      expect(formatTaxLabel(DEFAULT_ESTIMATED_HST_RATE, true)).toBe('Estimated HST (13%)');
    });

    it('formats authoritative integer percent rates cleanly', () => {
      expect(formatTaxLabel(0.13, false)).toBe('Estimated Tax (13%)');
      expect(formatTaxLabel(0.05, false)).toBe('Estimated Tax (5%)');
    });

    it('formats authoritative fractional percent rates cleanly', () => {
      expect(formatTaxLabel(0.135, false)).toBe('Estimated Tax (13.5%)');
    });

    it('formats 0% rate as Estimated Tax', () => {
      expect(formatTaxLabel(0, false)).toBe('Estimated Tax');
    });
  });

  describe('saveCachedTaxConfig & loadCachedTaxConfig persistence', () => {
    it('persists and loads a valid cached configuration', async () => {
      await saveCachedTaxConfig(0.08);
      const loaded = await loadCachedTaxConfig();
      expect(loaded).not.toBeNull();
      expect(loaded?.taxRate).toBe(0.08);
      expect(typeof loaded?.verifiedAt).toBe('number');
    });

    it('returns null if stored data is expired or corrupted', async () => {
      const expired: CachedTaxConfig = {
        taxRate: 0.13,
        verifiedAt: baseNow - TAX_CACHE_MAX_AGE_MS - 5000,
      };
      await AsyncStorage.setItem(TAX_CONFIG_CACHE_KEY, JSON.stringify(expired));
      const loaded = await loadCachedTaxConfig(baseNow);
      expect(loaded).toBeNull();
    });

    it('clears cached configuration properly', async () => {
      await saveCachedTaxConfig(0.13);
      await clearCachedTaxConfig();
      const loaded = await loadCachedTaxConfig();
      expect(loaded).toBeNull();
    });
  });

  describe('resolveCartTax pure resolution logic', () => {
    it('respects backend rate when backend is available', () => {
      const res = resolveCartTax({
        settingsData: { taxRate: 0.13 },
        isLoading: false,
        isError: false,
      });
      expect(res.status).toBe('backend');
      expect(res.taxRate).toBe(0.13);
      expect(res.taxLabel).toBe('Estimated Tax (13%)');
      expect(res.isCalculating).toBe(false);
      expect(res.isFallback).toBe(false);
      expect(res.notice).toBeNull();
    });

    it('respects a legitimate 0% rate from the backend', () => {
      const res = resolveCartTax({
        settingsData: { taxRate: 0 },
        isLoading: false,
        isError: false,
      });
      expect(res.status).toBe('backend');
      expect(res.taxRate).toBe(0);
      expect(res.taxLabel).toBe('Estimated Tax');
      expect(res.isFallback).toBe(false);
      expect(res.notice).toBeNull();
    });

    it('respects other valid rates from the backend (e.g. 5%)', () => {
      const res = resolveCartTax({
        settingsData: { taxRate: 0.05 },
        isLoading: false,
        isError: false,
      });
      expect(res.status).toBe('backend');
      expect(res.taxRate).toBe(0.05);
      expect(res.taxLabel).toBe('Estimated Tax (5%)');
      expect(res.isFallback).toBe(false);
      expect(res.notice).toBeNull();
    });

    it('returns loading state with Calculating tax... when backend is loading without cache', () => {
      const res = resolveCartTax({
        settingsData: null,
        isLoading: true,
        isError: false,
        cachedConfig: null,
      });
      expect(res.status).toBe('loading');
      expect(res.isCalculating).toBe(true);
      expect(res.taxLabel).toBe('Estimated Tax');
      expect(res.isFallback).toBe(false);
      expect(res.notice).toBeNull();
    });

    it('activates 13% Ontario HST fallback with notice when backend fails and no cache exists', () => {
      const res = resolveCartTax({
        settingsData: null,
        isLoading: false,
        isError: true,
        cachedConfig: null,
      });
      expect(res.status).toBe('fallback');
      expect(res.taxRate).toBe(0.13);
      expect(res.taxLabel).toBe('Estimated HST (13%)');
      expect(res.isCalculating).toBe(false);
      expect(res.isFallback).toBe(true);
      expect(res.notice).toBe(ESTIMATED_HST_FALLBACK_NOTICE);
    });

    it('prefers valid verified cached configuration over 13% fallback when backend fails', () => {
      const res = resolveCartTax({
        settingsData: null,
        isLoading: false,
        isError: true,
        cachedConfig: {
          taxRate: 0.07,
          verifiedAt: baseNow - 1000,
        },
      });
      expect(res.status).toBe('cached');
      expect(res.taxRate).toBe(0.07);
      expect(res.taxLabel).toBe('Estimated Tax (7%)');
      expect(res.isCalculating).toBe(false);
      expect(res.isFallback).toBe(false);
      expect(res.notice).toBeNull();
    });

    it('falls back to 13% HST if cached configuration is invalid or missing when backend fails', () => {
      const res = resolveCartTax({
        settingsData: null,
        isLoading: false,
        isError: true,
        cachedConfig: null,
      });
      expect(res.status).toBe('fallback');
      expect(res.taxRate).toBe(0.13);
      expect(res.isFallback).toBe(true);
      expect(res.notice).toBe(ESTIMATED_HST_FALLBACK_NOTICE);
    });
  });
});
