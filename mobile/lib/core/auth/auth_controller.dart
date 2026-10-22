import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';

class AuthController extends Notifier<AuthState> {
  @override
  AuthState build() {
    _restore();
    return const AuthRestoring();
  }

  Future<void> _restore() async {
    Session? session;
    try {
      session = await ref.read(authRepositoryProvider).restore();
    } catch (_) {
      session = null;
    }
    // A login or logout in the meantime wins.
    if (!ref.mounted || state is! AuthRestoring) {
      return;
    }
    state = session == null ? const Unauthenticated() : _signedIn(session);
  }

  Future<void> login(String email, String password) async {
    final session = await ref
        .read(authRepositoryProvider)
        .login(email: email, password: password);
    state = _signedIn(session);
  }

  Future<void> logout() async {
    await ref.read(authRepositoryProvider).logout();
    state = const Unauthenticated();
  }

  /// Shows the role picker again, e.g. from the profile tab. Ignored when
  /// there is nothing to choose.
  void openRolePicker() {
    if (state case Authenticated(
      :final session,
    ) when session.choices.length > 1) {
      state = Authenticated(session, choosingRole: true);
    }
  }

  /// Uses the app as [choice], one of [Session.choices], and closes the role
  /// picker.
  void choose(SessionChoice choice) {
    if (state case Authenticated(
      :final session,
    ) when session.choices.contains(choice)) {
      state = Authenticated(
        session.copyWith(
          activeRole: choice.role,
          activeProfileId: choice.profile?.id,
        ),
      );
    }
  }

  /// A fresh sign-in starts on the role picker unless there is only one
  /// choice.
  static Authenticated _signedIn(Session session) =>
      Authenticated(session, choosingRole: session.choices.length > 1);
}

final authControllerProvider = NotifierProvider<AuthController, AuthState>(
  AuthController.new,
);
