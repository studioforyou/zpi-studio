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
