import { FirebaseError } from 'firebase/app';
import {
  EmailAuthProvider,
  reauthenticateWithCredential,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
  updatePassword,
  type Auth,
} from 'firebase/auth';
import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import {
  AuthFailure,
  PasswordChangeFailure,
  PasswordResetFailure,
  type AuthFailureReason,
  type PasswordChangeFailureReason,
  type PasswordResetFailureReason,
} from './authFailure';
import type { AuthService } from './authService';
import type { MeResponse, Session } from './types';

/** `account.status` of the only accounts that may use the panel. */
const ACTIVE_STATUS = 'ACTIVE';
/** The only role that may use the panel. */
const ADMIN_ROLE = 'ADMIN';

/**
 * Signs in with Firebase Authentication, then asks the backend who the user is. Firebase only
 * proves the identity; status and roles come from `GET /api/v1/me`.
 */
export class FirebaseAuthService implements AuthService {
  private readonly auth: Auth;
  private readonly fetchMe: () => Promise<MeResponse>;

  constructor(auth: Auth, fetchMe: () => Promise<MeResponse>) {
    this.auth = auth;
    this.fetchMe = fetchMe;
  }

  async login(email: string, password: string): Promise<Session> {
    try {
      await signInWithEmailAndPassword(this.auth, email.trim(), password);
    } catch (error) {
      throw new AuthFailure(firebaseReason(error));
    }

    // Signed in to Firebase from here on: sign out again if the panel cannot be used,
    // so no half-signed-in user stays behind.
    try {
      return toSession(await this.loadMe());
    } catch (error) {
      await signOut(this.auth);
      throw error;
    }
  }

  async restore(): Promise<Session | null> {
    // Firebase keeps the user signed in between visits; this waits until it has read the saved user.
    await this.auth.authStateReady();
    if (this.auth.currentUser === null) {
      return null;
    }
    try {
      return toSession(await this.loadMe());
    } catch (error) {
      // Without a connection the user stays signed in for the next visit; otherwise the account
      // cannot use the panel any more.
      if (!(error instanceof AuthFailure && error.reason === 'network')) {
        await signOut(this.auth);
      }
      return null;
    }
  }

  async getIdToken(): Promise<string | null> {
    // Firebase refreshes an expired token on its own.
    return (await this.auth.currentUser?.getIdToken()) ?? null;
  }

  logout(): Promise<void> {
    return signOut(this.auth);
  }

  async sendPasswordReset(email: string): Promise<void> {
    try {
      await sendPasswordResetEmail(this.auth, email.trim());
    } catch (error) {
      // An unknown address looks like a success, as it does with Firebase's enumeration protection.
      if (!(error instanceof FirebaseError && error.code === 'auth/user-not-found')) {
        throw new PasswordResetFailure(resetReason(error));
      }
    }
  }

  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    const user = this.auth.currentUser;
    if (user === null || user.email === null) {
      throw new PasswordChangeFailure('unknown');
    }
    try {
      // Firebase only changes the password of a recent sign-in; confirming the current password
      // makes it one and keeps someone at an unattended computer from changing it.
      await reauthenticateWithCredential(
        user,
        EmailAuthProvider.credential(user.email, currentPassword),
      );
      await updatePassword(user, newPassword);
    } catch (error) {
      throw new PasswordChangeFailure(changeReason(error));
    }
  }

  private async loadMe(): Promise<MeResponse> {
    try {
      return await this.fetchMe();
    } catch (error) {
      throw new AuthFailure(apiReason(error));
    }
  }
}

function toSession(me: MeResponse): Session {
  if (me.status !== ACTIVE_STATUS) {
    throw new AuthFailure(me.status === 'PENDING_APPROVAL' ? 'pendingApproval' : 'accountInactive');
  }
  if (!me.roles.includes(ADMIN_ROLE)) {
    throw new AuthFailure('notAdmin');
  }
  return {
    accountId: me.accountId,
    email: me.email,
    displayName: me.displayName,
    roles: me.roles,
  };
}

function firebaseReason(error: unknown): AuthFailureReason {
  if (!(error instanceof FirebaseError)) {
    return 'unknown';
  }
  switch (error.code) {
    case 'auth/invalid-credential':
    case 'auth/invalid-email':
    case 'auth/user-not-found':
    case 'auth/wrong-password':
      return 'invalidCredentials';
    case 'auth/user-disabled':
      return 'accountInactive';
    case 'auth/too-many-requests':
      return 'tooManyAttempts';
    case 'auth/network-request-failed':
      return 'network';
    default:
      return 'unknown';
  }
}

function resetReason(error: unknown): PasswordResetFailureReason {
  if (!(error instanceof FirebaseError)) {
    return 'unknown';
  }
  switch (error.code) {
    case 'auth/invalid-email':
    case 'auth/missing-email':
      return 'invalidEmail';
    case 'auth/too-many-requests':
      return 'tooManyAttempts';
    case 'auth/network-request-failed':
      return 'network';
    default:
      return 'unknown';
  }
}

function changeReason(error: unknown): PasswordChangeFailureReason {
  if (!(error instanceof FirebaseError)) {
    return 'unknown';
  }
  switch (error.code) {
    case 'auth/invalid-credential':
    case 'auth/wrong-password':
      return 'wrongPassword';
    case 'auth/weak-password':
    case 'auth/password-does-not-meet-requirements':
      return 'weakPassword';
    case 'auth/too-many-requests':
      return 'tooManyAttempts';
    case 'auth/network-request-failed':
      return 'network';
    default:
      return 'unknown';
  }
}

function apiReason(error: unknown): AuthFailureReason {
  if (!(error instanceof ApiError)) {
    return 'unknown';
  }
  switch (error.code) {
    case NETWORK_ERROR:
      return 'network';
    case 'ACCOUNT_NOT_FOUND':
      return 'accountNotFound';
    default:
      return 'unknown';
  }
}
