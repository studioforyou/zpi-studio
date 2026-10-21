import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { configureApiClient } from '@/shared/api/apiClient';
import { authService } from './authService';
import type { Session } from './types';

type AuthContextValue = {
  session: Session | null;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  // TODO(auth): token storage decision pending; the token lives in memory only.
  const accessToken = useRef<string | null>(null);

  useEffect(() => {
    configureApiClient({
      getAccessToken: () => accessToken.current,
      onUnauthorized: () => {
        accessToken.current = null;
        setSession(null);
      },
    });
  }, []);

  const value: AuthContextValue = {
    session,
    isAuthenticated: session !== null,
    login: async (email, password) => {
      const response = await authService.login(email, password);
      accessToken.current = response.accessToken;
      setSession(response.account);
    },
    logout: async () => {
      await authService.logout();
      accessToken.current = null;
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
