import type { LoginResponse } from './types';

export interface AuthService {
  login(email: string, password: string): Promise<LoginResponse>;
  logout(): Promise<void>;
}

// TODO(auth): replace with POST /api/v1/auth/login
class FakeAuthService implements AuthService {
  async login(email: string, password: string): Promise<LoginResponse> {
    if (!email.trim() || !password.trim()) {
      throw new Error('E-mail and password are required.');
    }
    return {
      accessToken: 'fake-access-token',
      refreshToken: 'fake-refresh-token',
      account: {
        accountId: 'fake-admin',
        email: email.trim(),
        displayName: 'Administrator',
        roles: ['ADMIN'],
      },
    };
  }

  async logout(): Promise<void> {}
}

export const authService: AuthService = new FakeAuthService();
