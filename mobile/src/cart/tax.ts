import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState } from 'react';

/**
 * Default fallback Ontario HST rate (13%) used for customer estimates
 * when store settings cannot be retrieved from the backend.
 */
export const DEFAULT_ESTIMATED_HST_RATE = 0.13;

/**
 * Subtle notice informing the customer that standard Ontario HST is used
 * as an estimate until final taxes are confirmed at checkout.
 */
export const ESTIMATED_HST_FALLBACK_NOTICE =
  'Estimated using standard Ontario HST. Final taxes are confirmed at checkout.';

/**
 * Persistent storage key for cached store tax configuration.
 */
export const TAX_CONFIG_CACHE_KEY = 'lgb.tax.config.cache.v1';

/**
 * Cache freshness lifetime: 24 hours.
 * Tax rates in Ontario change infrequently (governed by provincial and federal tax acts).
 * A 24-hour window ensures reliable offline estimation while reflecting administrative
 * adjustments within a day of recovery.
 */
export const TAX_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000;

export interface CachedTaxConfig {
  taxRate: number;
  verifiedAt: number;
}

/**
 * Validates that a candidate tax rate is a finite, non-negative rate within reasonable bounds (0% to 100%).
 */
export function isValidTaxRate(rate: unknown): rate is number {
  return (
    typeof rate === 'number' &&
    Number.isFinite(rate) &&
    rate >= 0 &&
    rate <= 1.0
  );
}

/**
 * Validates that a cached tax configuration entry is well-formed, untampered, and unexpired.
 *
 * Rules:
 * 1. Must be a non-null object with numeric `taxRate` and `verifiedAt`.
 * 2. `taxRate` must pass `isValidTaxRate` (0% <= rate <= 100%).
 * 3. `verifiedAt` must be a valid past/current timestamp (> 0 and <= now).
 * 4. Elapsed time `now - verifiedAt` must not exceed `maxAgeMs` (default 24 hours).
 */
export function isValidTaxCache(
  cache: unknown,
  now = Date.now(),
  maxAgeMs = TAX_CACHE_MAX_AGE_MS,
): cache is CachedTaxConfig {
  if (!cache || typeof cache !== 'object') {
    return false;
  }
  const candidate = cache as Record<string, unknown>;
  if (!isValidTaxRate(candidate.taxRate)) {
    return false;
  }
  const verifiedAt = candidate.verifiedAt;
  if (
    typeof verifiedAt !== 'number' ||
    !Number.isFinite(verifiedAt) ||
    verifiedAt <= 0 ||
    verifiedAt > now
  ) {
    return false;
  }
  const age = now - verifiedAt;
  return age <= maxAgeMs;
}

/**
 * Formats the tax label according to the rate and fallback status.
 *
 * - Fallback: "Estimated HST (13%)"
 * - 0% rate: "Estimated Tax (0%)" or "Estimated Tax" (respects backend 0%)
 * - Valid backend rate: "Estimated Tax (13%)", "Estimated Tax (5%)", etc.
 */
export function formatTaxLabel(taxRate: number, isFallback = false): string {
  if (isFallback) {
    return 'Estimated HST (13%)';
  }
  if (taxRate <= 0) {
    return 'Estimated Tax';
  }
  const percent = Math.round(taxRate * 10000) / 100;
  const formattedPercent =
    percent % 1 === 0 ? percent.toFixed(0) : percent.toFixed(1);
  return `Estimated Tax (${formattedPercent}%)`;
}

/**
 * Persists a verified tax rate from the backend to local storage with timestamp.
 */
export async function saveCachedTaxConfig(rate: number): Promise<void> {
  if (!isValidTaxRate(rate)) {
    return;
  }
  try {
    const payload: CachedTaxConfig = {
      taxRate: rate,
      verifiedAt: Date.now(),
    };
    await AsyncStorage.setItem(TAX_CONFIG_CACHE_KEY, JSON.stringify(payload));
  } catch {
    // Non-blocking storage write failure
  }
}

/**
 * Reads and verifies the cached tax configuration from local storage.
 * Returns null if missing, malformed, or expired.
 */
export async function loadCachedTaxConfig(
  now = Date.now(),
  maxAgeMs = TAX_CACHE_MAX_AGE_MS,
): Promise<CachedTaxConfig | null> {
  try {
    const raw = await AsyncStorage.getItem(TAX_CONFIG_CACHE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw);
    if (isValidTaxCache(parsed, now, maxAgeMs)) {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}

export async function clearCachedTaxConfig(): Promise<void> {
  try {
    await AsyncStorage.removeItem(TAX_CONFIG_CACHE_KEY);
  } catch {
    // Non-blocking
  }
}

export type TaxResolutionStatus = 'backend' | 'cached' | 'loading' | 'fallback';

export interface TaxResolution {
  status: TaxResolutionStatus;
  taxRate: number;
  taxLabel: string;
  isCalculating: boolean;
  isFallback: boolean;
  notice: string | null;
}

/**
 * Pure resolution function that computes the active tax rate, label, and messaging.
 */
export function resolveCartTax({
  settingsData,
  isLoading,
  isError,
  cachedConfig,
}: {
  settingsData?: { taxRate?: number | null } | null;
  isLoading: boolean;
  isError: boolean;
  cachedConfig?: CachedTaxConfig | null;
}): TaxResolution {
  // 1. Authoritative Backend Available (Success)
  const backendTaxRate = settingsData?.taxRate;
  if (!isError && isValidTaxRate(backendTaxRate)) {
    return {
      status: 'backend',
      taxRate: backendTaxRate,
      taxLabel: formatTaxLabel(backendTaxRate, false),
      isCalculating: false,
      isFallback: false,
      notice: null,
    };
  }

  // 2. Loading State (no verified cached configuration yet)
  if (isLoading && !cachedConfig) {
    return {
      status: 'loading',
      taxRate: 0,
      taxLabel: 'Estimated Tax',
      isCalculating: true,
      isFallback: false,
      notice: null,
    };
  }

  // 3. Cached Configuration (Backend error or loading with verified cached rate)
  if (cachedConfig && isValidTaxRate(cachedConfig.taxRate)) {
    return {
      status: 'cached',
      taxRate: cachedConfig.taxRate,
      taxLabel: formatTaxLabel(cachedConfig.taxRate, false),
      isCalculating: false,
      isFallback: false,
      notice: null,
    };
  }

  // 4. Fallback State: Backend unavailable and no valid cached configuration
  if (isError || !isLoading) {
    return {
      status: 'fallback',
      taxRate: DEFAULT_ESTIMATED_HST_RATE,
      taxLabel: 'Estimated HST (13%)',
      isCalculating: false,
      isFallback: true,
      notice: ESTIMATED_HST_FALLBACK_NOTICE,
    };
  }

  // Default initial loading
  return {
    status: 'loading',
    taxRate: 0,
    taxLabel: 'Estimated Tax',
    isCalculating: true,
    isFallback: false,
    notice: null,
  };
}

/**
 * React hook that coordinates React Query settings query and persistent tax cache.
 */
export function useCartTax(
  settings: {
    data?: { taxRate?: number | null } | null;
    isLoading: boolean;
    isError: boolean;
  },
  options?: { now?: number; maxAgeMs?: number },
): TaxResolution {
  const [cachedConfig, setCachedConfig] = useState<CachedTaxConfig | null>(null);

  // Load verified cache on mount
  useEffect(() => {
    let cancelled = false;
    void loadCachedTaxConfig(options?.now, options?.maxAgeMs).then((config) => {
      if (!cancelled && config) {
        setCachedConfig(config);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [options?.now, options?.maxAgeMs]);

  // When backend returns a fresh valid rate, persist it to cache
  useEffect(() => {
    if (!settings.isError && isValidTaxRate(settings.data?.taxRate)) {
      void saveCachedTaxConfig(settings.data.taxRate);
      setCachedConfig({
        taxRate: settings.data.taxRate,
        verifiedAt: Date.now(),
      });
    }
  }, [settings.data?.taxRate, settings.isError]);

  return resolveCartTax({
    settingsData: settings.data,
    isLoading: settings.isLoading,
    isError: settings.isError,
    cachedConfig,
  });
}
