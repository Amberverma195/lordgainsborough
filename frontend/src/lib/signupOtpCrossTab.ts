/**
 * Cross-tab sync for signup OTP challenge UI state.
 * Uses BroadcastChannel when available, with a localStorage storage-event fallback.
 *
 * Backend enforcement remains mandatory — this is UX sync only, not duplicate-send protection.
 */

import type { SignupOtpChallengeState, SignupOtpCode } from './signupOtp';

export const SIGNUP_OTP_SYNC_CHANNEL = 'lordgainsborough-signup-otp';
export const SIGNUP_OTP_SYNC_STORAGE_KEY = 'signupOtpChallengeSync';

/** Safe payload — never includes OTP, digests, tokens, or provider details. */
export interface SignupOtpCrossTabPayload {
  type: 'challenge-updated' | 'verified' | 'cleared';
  challengeId?: string;
  serverTime?: string;
  nextResendAt?: string;
  resendsRemaining?: number;
  challengeExpiresAt?: string;
  /** Logical result code from the backend response. */
  code?: SignupOtpCode;
  email?: string;
  originTabId: string;
  updatedAt: number;
}

export function toCrossTabPayload(
  state: SignupOtpChallengeState,
  type: SignupOtpCrossTabPayload['type'] = 'challenge-updated',
  originTabId: string
): SignupOtpCrossTabPayload {
  return {
    type,
    challengeId: state.challengeId,
    serverTime: state.serverTime,
    nextResendAt: state.nextResendAt,
    resendsRemaining: state.resendsRemaining,
    challengeExpiresAt: state.challengeExpiresAt,
    code: state.code,
    email: state.email,
    originTabId,
    updatedAt: Date.now(),
  };
}

export function applyCrossTabPayload(
  previous: SignupOtpChallengeState | null,
  payload: SignupOtpCrossTabPayload
): SignupOtpChallengeState | null {
  if (payload.type === 'cleared') {
    return null;
  }

  return {
    email: payload.email || previous?.email || '',
    challengeId: payload.challengeId ?? previous?.challengeId,
    serverTime: payload.serverTime ?? previous?.serverTime,
    nextResendAt: payload.nextResendAt ?? previous?.nextResendAt,
    challengeExpiresAt: payload.challengeExpiresAt ?? previous?.challengeExpiresAt,
    otpExpiresAt: previous?.otpExpiresAt,
    resendsRemaining: typeof payload.resendsRemaining === 'number'
      ? payload.resendsRemaining
      : previous?.resendsRemaining,
    code: payload.code ?? previous?.code,
    message: previous?.message,
  };
}

export function parseCrossTabPayload(raw: string): SignupOtpCrossTabPayload | null {
  try {
    const parsed = JSON.parse(raw) as SignupOtpCrossTabPayload & {
      otp?: unknown;
      token?: unknown;
      digest?: unknown;
    };
    if (!parsed || typeof parsed !== 'object') return null;
    if (parsed.type !== 'challenge-updated' && parsed.type !== 'verified' && parsed.type !== 'cleared') {
      return null;
    }
    // Strip any accidental sensitive fields.
    delete parsed.otp;
    delete parsed.token;
    delete parsed.digest;
    return {
      type: parsed.type,
      challengeId: parsed.challengeId,
      serverTime: parsed.serverTime,
      nextResendAt: parsed.nextResendAt,
      resendsRemaining: parsed.resendsRemaining,
      challengeExpiresAt: parsed.challengeExpiresAt,
      code: parsed.code,
      email: parsed.email,
      originTabId: parsed.originTabId || '',
      updatedAt: typeof parsed.updatedAt === 'number' ? parsed.updatedAt : Date.now(),
    };
  } catch {
    return null;
  }
}

export function createTabId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `tab-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

type BroadcastLike = {
  postMessage: (data: SignupOtpCrossTabPayload) => void;
  close: () => void;
  onmessage: ((event: { data: SignupOtpCrossTabPayload }) => void) | null;
};

export type SignupOtpCrossTabHandle = {
  tabId: string;
  publish: (payload: SignupOtpCrossTabPayload) => void;
  close: () => void;
};

/**
 * Subscribe to cross-tab challenge updates.
 * @param onRemote called for messages from other tabs only
 */
export function subscribeSignupOtpCrossTab(
  onRemote: (payload: SignupOtpCrossTabPayload) => void,
  options?: {
    tabId?: string;
    createBroadcastChannel?: (name: string) => BroadcastLike | null;
    storage?: Storage | null;
    addStorageListener?: (listener: (event: StorageEvent) => void) => () => void;
  }
): SignupOtpCrossTabHandle {
  const tabId = options?.tabId ?? createTabId();
  const storage = options?.storage !== undefined
    ? options.storage
    : (typeof localStorage !== 'undefined' ? localStorage : null);

  let channel: BroadcastLike | null = null;
  const createChannel = options?.createBroadcastChannel
    ?? ((name: string) => {
      if (typeof BroadcastChannel === 'undefined') return null;
      try {
        return new BroadcastChannel(name) as BroadcastLike;
      } catch {
        return null;
      }
    });

  channel = createChannel(SIGNUP_OTP_SYNC_CHANNEL);
  if (channel) {
    channel.onmessage = (event) => {
      const payload = event?.data;
      if (!payload || payload.originTabId === tabId) return;
      onRemote(payload);
    };
  }

  const storageListener = (event: StorageEvent) => {
    if (event.key !== SIGNUP_OTP_SYNC_STORAGE_KEY || !event.newValue) return;
    const payload = parseCrossTabPayload(event.newValue);
    if (!payload || payload.originTabId === tabId) return;
    onRemote(payload);
  };

  let removeStorageListener: (() => void) | undefined;
  if (options?.addStorageListener) {
    removeStorageListener = options.addStorageListener(storageListener);
  } else if (typeof window !== 'undefined') {
    window.addEventListener('storage', storageListener);
    removeStorageListener = () => window.removeEventListener('storage', storageListener);
  }

  const publish = (payload: SignupOtpCrossTabPayload) => {
    const safe: SignupOtpCrossTabPayload = {
      ...payload,
      originTabId: payload.originTabId || tabId,
      updatedAt: payload.updatedAt || Date.now(),
    };
    // Never allow sensitive fields onto the wire.
    const sanitized = parseCrossTabPayload(JSON.stringify(safe));
    if (!sanitized) return;

    if (channel) {
      try {
        channel.postMessage(sanitized);
      } catch {
        // Fall through to storage.
      }
    }
    if (storage) {
      try {
        // storage events fire in *other* documents only; write a unique value each time.
        storage.setItem(SIGNUP_OTP_SYNC_STORAGE_KEY, JSON.stringify({
          ...sanitized,
          _n: Math.random(),
        }));
      } catch {
        // Quota / private mode — ignore.
      }
    }
  };

  return {
    tabId,
    publish,
    close: () => {
      try {
        channel?.close();
      } catch {
        // ignore
      }
      channel = null;
      removeStorageListener?.();
    },
  };
}
