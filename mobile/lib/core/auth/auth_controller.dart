import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
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
    state = session == null ? const Unauthenticated() : Authenticated(session);
  }

  Future<void> login(String email, String password) async {
    final session = await ref
        .read(authRepositoryProvider)
        .login(email: email, password: password);
    state = Authenticated(session);
  }

  Future<void> logout() async {
    await ref.read(authRepositoryProvider).logout();
    state = const Unauthenticated();
  }

  void selectRole(AppRole role) {
    if (state case Authenticated(
      :final session,
    ) when session.roles.contains(role)) {
      state = Authenticated(session.copyWith(activeRole: role));
    }
  }

  void selectProfile(String profileId) {
    if (state case Authenticated(
      :final session,
    ) when session.profiles.any((p) => p.id == profileId)) {
      state = Authenticated(session.copyWith(activeProfileId: profileId));
    }
  }
}

final authControllerProvider = NotifierProvider<AuthController, AuthState>(
  AuthController.new,
);
