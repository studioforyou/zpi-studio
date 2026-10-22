import 'package:mobile/core/auth/session.dart';

sealed class AuthState {
  const AuthState();
}

/// The app has just started and is checking whether a user is still signed in
/// from an earlier run.
final class AuthRestoring extends AuthState {
  const AuthRestoring();
}

final class Unauthenticated extends AuthState {
  const Unauthenticated();
}

final class Authenticated extends AuthState {
  const Authenticated(this.session, {this.choosingRole = false});

  final Session session;

  /// Whether the user has yet to pick how to use the app (see
  /// [Session.choices]); the router shows the role picker until they do.
  final bool choosingRole;
}
