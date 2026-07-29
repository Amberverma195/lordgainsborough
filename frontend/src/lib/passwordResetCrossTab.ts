import {
  PasswordResetChallengeState,
  toSafePasswordResetState,
} from './passwordReset';

export const PASSWORD_RESET_SYNC_CHANNEL = 'lordgainsborough-password-reset';
export const PASSWORD_RESET_SYNC_STORAGE_KEY = 'passwordResetChallengeSync';

export interface PasswordResetCrossTabPayload {
  type: 'challenge-updated' | 'completed' | 'cleared';
  email?: string;
  challengeId?: string;
  serverTime?: string;
  nextResendAt?: string;
  codeExpiresAt?: string;
  challengeExpiresAt?: string;
  resendsRemaining?: number;
  retryAfterSeconds?: number;
  observedAt?: number;
  originTabId: string;
  updatedAt: number;
}

export function toPasswordResetCrossTabPayload(
  state: PasswordResetChallengeState | null,
  type: PasswordResetCrossTabPayload['type'],
  originTabId: string
): PasswordResetCrossTabPayload {
  return {
    type,
    email: state?.email,
    challengeId: state?.challengeId,
    serverTime: state?.serverTime,
    nextResendAt: state?.nextResendAt,
    codeExpiresAt: state?.codeExpiresAt,
    challengeExpiresAt: state?.challengeExpiresAt,
    resendsRemaining: state?.resendsRemaining,
    retryAfterSeconds: state?.retryAfterSeconds,
    observedAt: state?.observedAt,
    originTabId,
    updatedAt: Date.now(),
  };
}

export function parsePasswordResetCrossTabPayload(
  raw: string
): PasswordResetCrossTabPayload | null {
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    if (
      !parsed
      || typeof parsed !== 'object'
      || (
        parsed.type !== 'challenge-updated'
        && parsed.type !== 'completed'
        && parsed.type !== 'cleared'
      )
    ) {
      return null;
    }

    const type = parsed.type as PasswordResetCrossTabPayload['type'];
    const originTabId = typeof parsed.originTabId === 'string' ? parsed.originTabId : '';
    const updatedAt = typeof parsed.updatedAt === 'number' && Number.isFinite(parsed.updatedAt)
      ? parsed.updatedAt
      : Date.now();

    if (type !== 'challenge-updated') {
      return { type, originTabId, updatedAt };
    }

    const safe = toSafePasswordResetState(parsed);
    if (!safe) return null;
    return {
      type,
      ...safe,
      originTabId,
      updatedAt,
    };
  } catch {
    return null;
  }
}

export function applyPasswordResetCrossTabPayload(
  payload: PasswordResetCrossTabPayload
): PasswordResetChallengeState | null {
  if (payload.type !== 'challenge-updated') return null;
  return toSafePasswordResetState(payload);
}

function createTabId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `tab-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

type BroadcastLike = {
  postMessage: (data: PasswordResetCrossTabPayload) => void;
  close: () => void;
  onmessage: ((event: { data: PasswordResetCrossTabPayload }) => void) | null;
};

export interface PasswordResetCrossTabHandle {
  tabId: string;
  publish: (payload: PasswordResetCrossTabPayload) => void;
  close: () => void;
}

export function subscribePasswordResetCrossTab(
  onRemote: (payload: PasswordResetCrossTabPayload) => void,
  options?: {
    tabId?: string;
    createBroadcastChannel?: (name: string) => BroadcastLike | null;
    storage?: Storage | null;
    addStorageListener?: (listener: (event: StorageEvent) => void) => () => void;
  }
): PasswordResetCrossTabHandle {
  const tabId = options?.tabId ?? createTabId();
  const storage = options?.storage !== undefined
    ? options.storage
    : (typeof localStorage !== 'undefined' ? localStorage : null);
  const createChannel = options?.createBroadcastChannel
    ?? ((name: string) => {
      if (typeof BroadcastChannel === 'undefined') return null;
      try {
        return new BroadcastChannel(name) as BroadcastLike;
      } catch {
        return null;
      }
    });

  let channel = createChannel(PASSWORD_RESET_SYNC_CHANNEL);
  if (channel) {
    channel.onmessage = (event) => {
      try {
        const serialized = JSON.stringify(event?.data);
        const payload = parsePasswordResetCrossTabPayload(serialized);
        if (!payload || payload.originTabId === tabId) return;
        onRemote(payload);
      } catch {
        // Ignore malformed or cyclic cross-tab messages.
      }
    };
  }

  const storageListener = (event: StorageEvent) => {
    if (event.key !== PASSWORD_RESET_SYNC_STORAGE_KEY || !event.newValue) return;
    const payload = parsePasswordResetCrossTabPayload(event.newValue);
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

  const publish = (payload: PasswordResetCrossTabPayload) => {
    const safe = parsePasswordResetCrossTabPayload(JSON.stringify({
      ...payload,
      originTabId: payload.originTabId || tabId,
      updatedAt: payload.updatedAt || Date.now(),
    }));
    if (!safe) return;

    if (channel) {
      try {
        channel.postMessage(safe);
      } catch {
        // The localStorage fallback below still gets a chance.
      }
    }
    if (storage) {
      try {
        storage.setItem(PASSWORD_RESET_SYNC_STORAGE_KEY, JSON.stringify({
          ...safe,
          nonce: Math.random(),
        }));
      } catch {
        // Ignore quota and private-mode storage failures.
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
        // Ignore close failures.
      }
      channel = null;
      removeStorageListener?.();
    },
  };
}
