import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import { fetchCurrentUser, login } from '../api/auth';
import { onUnauthorized } from '../api/client';
import { ApiError } from '../api/errors';
import { SessionUser } from '../api/parse';
import { clearSessionToken, readSessionToken, writeSessionToken } from './secureSession';
import { shouldClearStoredSession } from './session';

export type AuthStatus = 'loading' | 'signedIn' | 'signedOut' | 'unreachable';

interface AuthContextValue {
  status: AuthStatus;
  user: SessionUser | null;
  signIn: (emailOrPhone: string, password: string) => Promise<SessionUser>;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<SessionUser | null>(null);

  const clearLocalSession = useCallback(async () => {
    await clearSessionToken();
    setUser(null);
    setStatus('signedOut');
  }, []);

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

  useEffect(() => onUnauthorized(() => {
    void clearLocalSession();
  }), [clearLocalSession]);

  const signIn = useCallback(async (emailOrPhone: string, password: string) => {
    try {
      const session = await login({ emailOrPhone, password });
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
        message: 'We could not reach the restaurant. Check your connection and try again.',
      });
    }
  }, []);

  const signOut = useCallback(async () => {
    await clearLocalSession();
  }, [clearLocalSession]);

  const value = useMemo(
    () => ({ status, user, signIn, signOut, refresh }),
    [status, user, signIn, signOut, refresh],
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
