import 'package:mobile/core/auth/session.dart';

sealed class AuthState {
  const AuthState();
}

final class Unauthenticated extends AuthState {
  const Unauthenticated();
}

final class Authenticated extends AuthState {
  const Authenticated(this.session);

  final Session session;
}
