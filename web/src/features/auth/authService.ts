import { firebaseAuth } from './firebase';
import { FirebaseAuthService } from './FirebaseAuthService';
import { fetchMe } from './meApi';
import type { Session } from './types';

export interface AuthService {
  /** Throws `AuthFailure` when signing in fails or the account cannot use the panel. */
  login(email: string, password: string): Promise<Session>;

  /**
   * Returns the session of the user still signed in from an earlier visit, or `null` when there is
   * none or the account can no longer use the panel.
   */
  restore(): Promise<Session | null>;

  /** The current Firebase ID token, or `null` when nobody is signed in. */
  getIdToken(): Promise<string | null>;

  logout(): Promise<void>;
}

export const authService: AuthService = new FirebaseAuthService(firebaseAuth, fetchMe);
