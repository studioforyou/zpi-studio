import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/choice_store.dart';
import 'package:mobile/core/auth/session.dart';

class AuthController extends Notifier<AuthState> {
  @override
  AuthState build() {
    _restore();
    return const AuthRestoring();
  }

  Future<void> _restore() async {
    Session? session;
    SessionChoice? lastChoice;
    try {
      session = await ref.read(authRepositoryProvider).restore();
      if (session != null) {
        lastChoice = await _readLastChoice(session);
      }
    } catch (_) {
      session = null;
    }
    // A login or logout in the meantime wins.
    if (!ref.mounted || state is! AuthRestoring) {
      return;
    }
    state = switch ((session, lastChoice)) {
      (null, _) => const Unauthenticated(),
      // The app reopens as it was left.
      (final session?, final choice?) => Authenticated(
        _applyChoice(session, choice),
      ),
      (final session?, null) => _signedIn(session),
    };
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

  /// Shows the role picker again from inside the app (the profile tab).
  /// Ignored when there is nothing to choose.
  void openRolePicker() {
    if (state case Authenticated(
      :final session,
    ) when session.choices.length > 1) {
      state = Authenticated(session, choosingRole: true, switchingRole: true);
    }
  }

  /// Closes the role picker opened from inside the app without changing
  /// anything; the router then goes back to where it was opened.
  void cancelRolePicker() {
    if (state case Authenticated(:final session, switchingRole: true)) {
      state = Authenticated(session);
    }
  }

  /// Uses the app as [choice], one of [Session.choices], closes the role
  /// picker and remembers the choice for the next app start.
  void choose(SessionChoice choice) {
    if (state case Authenticated(
      :final session,
    ) when session.choices.contains(choice)) {
      state = Authenticated(_applyChoice(session, choice));
      _rememberChoice(session.accountId, choice);
    }
  }

  /// The remembered choice, if it is still one of the account's choices.
  /// A storage failure only means the role picker is shown.
  Future<SessionChoice?> _readLastChoice(Session session) async {
    try {
      final key = await ref.read(choiceStoreProvider).read(session.accountId);
      return key == null ? null : session.choiceByKey(key);
    } catch (_) {
      return null;
    }
  }

  void _rememberChoice(String accountId, SessionChoice choice) {
    ref
        .read(choiceStoreProvider)
        .write(accountId, choice.key)
        .catchError((Object _) {});
  }

  static Session _applyChoice(Session session, SessionChoice choice) => session
      .copyWith(activeRole: choice.role, activeProfileId: choice.profile?.id);

  /// A fresh sign-in starts on the role picker unless there is only one
  /// choice.
  static Authenticated _signedIn(Session session) =>
      Authenticated(session, choosingRole: session.choices.length > 1);
}

final authControllerProvider = NotifierProvider<AuthController, AuthState>(
  AuthController.new,
);
