/// Why signing in failed, in terms the login screen can explain.
enum AuthFailureReason {
  /// Wrong e-mail or password.
  invalidCredentials,

  /// Firebase blocked further attempts for a while.
  tooManyAttempts,

  /// No connection to Firebase or the backend.
  network,

  /// The Firebase user has no account in the application.
  accountNotFound,

  /// Self-registration waiting for approval by the studio.
  pendingApproval,

  /// Any other non-active account (rejected, suspended, closed, disabled).
  accountInactive,

  /// The account has no role the mobile app supports.
  noSupportedRole,

  unknown,
}

class AuthFailure implements Exception {
  const AuthFailure(this.reason);

  final AuthFailureReason reason;

  @override
  String toString() => 'AuthFailure(${reason.name})';
}

/// Why sending the password reset e-mail failed.
enum PasswordResetFailureReason {
  /// The address is empty or not a valid e-mail.
  invalidEmail,

  /// Firebase blocked further requests for a while.
  tooManyAttempts,

  /// No connection to Firebase.
  network,

  unknown,
}

class PasswordResetFailure implements Exception {
  const PasswordResetFailure(this.reason);

  final PasswordResetFailureReason reason;

  @override
  String toString() => 'PasswordResetFailure(${reason.name})';
}
