/** Why signing in failed, in terms the login page can explain. */
export type AuthFailureReason =
  /** Wrong e-mail or password. */
  | 'invalidCredentials'
  /** Firebase blocked further attempts for a while. */
  | 'tooManyAttempts'
  /** No connection to Firebase or the backend. */
  | 'network'
  /** The Firebase user has no account in the application. */
  | 'accountNotFound'
  /** Self-registration waiting for approval by the studio. */
  | 'pendingApproval'
  /** Any other non-active account (invited, rejected, suspended, closed, disabled). */
  | 'accountInactive'
  /** An active account without the ADMIN role. */
  | 'notAdmin'
  | 'unknown';

export class AuthFailure extends Error {
  readonly reason: AuthFailureReason;

  constructor(reason: AuthFailureReason) {
    super(`Sign-in failed: ${reason}`);
    this.name = 'AuthFailure';
    this.reason = reason;
  }
}

/** Why the password reset e-mail could not be sent. */
export type PasswordResetFailureReason = 'invalidEmail' | 'tooManyAttempts' | 'network' | 'unknown';

export class PasswordResetFailure extends Error {
  readonly reason: PasswordResetFailureReason;

  constructor(reason: PasswordResetFailureReason) {
    super(`Password reset failed: ${reason}`);
    this.name = 'PasswordResetFailure';
    this.reason = reason;
  }
}

/** Why the password could not be changed. */
export type PasswordChangeFailureReason =
  /** The current password given to confirm the change is wrong. */
  | 'wrongPassword'
  /** Firebase rejected the new password as too weak. */
  | 'weakPassword'
  | 'tooManyAttempts'
  | 'network'
  | 'unknown';

export class PasswordChangeFailure extends Error {
  readonly reason: PasswordChangeFailureReason;

  constructor(reason: PasswordChangeFailureReason) {
    super(`Password change failed: ${reason}`);
    this.name = 'PasswordChangeFailure';
    this.reason = reason;
  }
}
