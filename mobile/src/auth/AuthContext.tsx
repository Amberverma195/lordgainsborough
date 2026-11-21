import { revokeAndClearSession } from './signOut';
import { clearDeletedAccountData } from './deleteAccountData';
import { accountWillChange } from './lifecycle';
import AsyncStorage from '@react-native-async-storage/async-storage';
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';

import { fetchCurrentUser, login } from '../api/auth';
import { onUnauthorized } from '../api/client';
import { ApiError } from '../api/errors';
import { SessionUser } from '../api/parse';
import { clearAllConfirmedOrders } from '../checkout/confirmationCache';
import {
  clearAllCheckoutIdempotencyKeys,
  clearCheckoutIdempotencyKey,
} from '../checkout/idempotency';
import { queryClient } from '../query/client';
import {
  clearAccountTrackingTokens,
  clearSessionToken,
  readSessionToken,
  writeSessionToken,
} from './secureSession';
import { shouldClearStoredSession } from './session';

export type AuthStatus = 'loading' | 'signedIn' | 'signedOut' | 'unreachable';

export interface AuthContextValue {
  status: AuthStatus;
  user: SessionUser | null;
  signIn: (emailOrPhone: string, password: string) => Promise<SessionUser>;
  establishSession: (session: SessionUser) => Promise<void>;
  updateUser: (updatedUser: SessionUser) => Promise<void>;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
  finishAccountDeletion: (email: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<SessionUser | null>(null);

  const clearLocalSession = useCallback(async () => {
    accountWillChange();
    const priorEmail = user?.email;
    await clearSessionToken();
    clearAllConfirmedOrders();
    queryClient.clear();
    try {
      if (priorEmail) {
        await clearCheckoutIdempotencyKey(AsyncStorage, priorEmail);
        await clearAccountTrackingTokens(priorEmail);
      }
      await clearAllCheckoutIdempotencyKeys(AsyncStorage);
      await clearAccountTrackingTokens(null);
    } catch {
      // Ignore storage cleanup failures
    }
    setUser(null);
    setStatus('signedOut');
  }, [user?.email]);

  const refresh = useCallback(async () => {
    const token = await readSessionToken();
    if (!token) {
      setUser(null);
      setStatus('signedOut');
      return;
    }
    try {
      const current = await fetchCurrentUser(token);
      setUser(current);
      setStatus('signedIn');
    } catch (error) {
      if (shouldClearStoredSession(error, true)) {
        await clearLocalSession();
        return;
      }
      setUser(null);
      setStatus('unreachable');
    }
  }, [clearLocalSession]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const token = await readSessionToken();
      if (cancelled) {
        return;
      }
      if (!token) {
        setUser(null);
        setStatus('signedOut');
        return;
      }
      try {
        const current = await fetchCurrentUser(token);
        if (cancelled) {
          return;
        }
        setUser(current);
        setStatus('signedIn');
      } catch (error) {
        if (cancelled) {
          return;
        }
        if (shouldClearStoredSession(error, true)) {
          await clearLocalSession();
          return;
        }
        setUser(null);
        setStatus('unreachable');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [clearLocalSession]);

  useEffect(
    () =>
      onUnauthorized(() => {
        void clearLocalSession();
      }),
    [clearLocalSession],
  );

  const signIn = useCallback(
    async (emailOrPhone: string, password: string) => {
      try {
        const priorEmail = user?.email;
        const session = await login({ emailOrPhone, password });
        clearAllConfirmedOrders();
        queryClient.clear();
        try {
          if (
            priorEmail &&
            priorEmail.trim().toLowerCase() !==
              session.email.trim().toLowerCase()
          ) {
            await clearCheckoutIdempotencyKey(AsyncStorage, priorEmail);
            await clearAccountTrackingTokens(priorEmail);
          }
          await clearAllCheckoutIdempotencyKeys(AsyncStorage);
          await clearAccountTrackingTokens(null);
        } catch {
          // Ignore storage cleanup failures
        }
        accountWillChange();
        await writeSessionToken(session.token);
        setUser(session);
        setStatus('signedIn');
        return session;
      } catch (error) {
        if (error instanceof ApiError) {
          throw error;
        }
        throw new ApiError({
          status: 0,
          code: 'NETWORK_ERROR',
          message:
            'We could not reach the restaurant. Check your connection and try again.',
        });
      }
    },
    [user],
  );

  const establishSession = useCallback(
    async (session: SessionUser) => {
      const priorEmail = user?.email;
      clearAllConfirmedOrders();
      queryClient.clear();
      try {
        if (
          priorEmail &&
          priorEmail.trim().toLowerCase() !== session.email.trim().toLowerCase()
        ) {
          await clearCheckoutIdempotencyKey(AsyncStorage, priorEmail);
          await clearAccountTrackingTokens(priorEmail);
        }
        await clearAllCheckoutIdempotencyKeys(AsyncStorage);
        await clearAccountTrackingTokens(null);
      } catch {
        // Ignore storage cleanup failures
      }
      accountWillChange();
      await writeSessionToken(session.token);
      setUser(session);
      setStatus('signedIn');
    },
    [user],
  );

  const updateUser = useCallback(async (updatedUser: SessionUser) => {
    if (updatedUser.token) {
      await writeSessionToken(updatedUser.token);
    }
    setUser(updatedUser);
    setStatus('signedIn');
  }, []);

  const signOut = useCallback(async () => {
    await revokeAndClearSession(clearLocalSession);
  }, [clearLocalSession]);

  const finishAccountDeletion = useCallback(async (email: string) => {
    await clearDeletedAccountData(email, () => {
      accountWillChange();
      setUser(null);
      setStatus('signedOut');
    });
  }, []);

  const value = useMemo(
    () => ({
      status,
      user,
      signIn,
      establishSession,
      updateUser,
      signOut,
      refresh,
      finishAccountDeletion,
    }),
    [
      status,
      user,
      signIn,
      establishSession,
      updateUser,
      signOut,
      refresh,
      finishAccountDeletion,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return value;
}
