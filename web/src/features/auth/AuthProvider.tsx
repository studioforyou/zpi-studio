import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { configureApiClient } from '@/shared/api/apiClient';
import { authService } from './authService';
import type { Session } from './types';

type AuthContextValue = {
  session: Session | null;
  isAuthenticated: boolean;
  /** True until the session saved by Firebase from an earlier visit has been checked. */
  isRestoring: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [isRestoring, setIsRestoring] = useState(true);

  useEffect(() => {
    configureApiClient({
      getAccessToken: () => authService.getIdToken(),
      onUnauthorized: () => {
        setSession(null);
        void authService.logout();
      },
    });

    let active = true;
    authService
      .restore()
      .then((restored) => {
        if (active) {
          setSession(restored);
        }
      })
      .finally(() => {
        if (active) {
          setIsRestoring(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  const value: AuthContextValue = {
    session,
    isAuthenticated: session !== null,
    isRestoring,
    login: async (email, password) => {
      setSession(await authService.login(email, password));
    },
    logout: async () => {
      await authService.logout();
      setSession(null);
    },
  };

  return <AuthContext value={value}>{children}</AuthContext>;
}

// oxlint-disable-next-line react/only-export-components -- the hook is part of the provider's API
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (value === null) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return value;
}
