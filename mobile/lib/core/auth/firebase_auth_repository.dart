import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/me_api.dart';
import 'package:mobile/core/auth/session.dart';

/// `account.status` of the only accounts that may use the app.
const _activeStatus = 'ACTIVE';

/// Signs in with Firebase Authentication, then asks the backend who the user
/// is. Firebase only proves the identity; status, roles and profiles come from
/// `GET /api/v1/me`.
class FirebaseAuthRepository implements AuthRepository {
  const FirebaseAuthRepository(this._auth, this._meApi);

  final FirebaseAuth _auth;
  final MeApi _meApi;

  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async {
    try {
      await _auth.signInWithEmailAndPassword(
        email: email.trim(),
        password: password,
      );
    } on FirebaseAuthException catch (e) {
      throw AuthFailure(_reasonFor(e));
    }

    // Signed in to Firebase from here on: sign out again if the app cannot
    // be used, so no half-signed-in user stays behind.
    try {
      return _toSession(await _meApi.fetchMe());
    } catch (_) {
      await _auth.signOut();
      rethrow;
    }
  }

  @override
  Future<Session?> restore() async {
    // Firebase keeps the user signed in between app starts; the first event
    // comes once it has read the saved user.
    final user = await _auth.authStateChanges().first;
    if (user == null) {
      return null;
    }
    try {
      return _toSession(await _meApi.fetchMe());
    } on AuthFailure catch (e) {
      // Without a connection the user stays signed in for the next start;
      // otherwise the account cannot use the app any more.
      if (e.reason != AuthFailureReason.network) {
        await _auth.signOut();
      }
      return null;
    }
  }

  @override
  Future<void> logout() => _auth.signOut();

  static Session _toSession(MeResponse me) {
    if (me.status != _activeStatus) {
      throw AuthFailure(
        me.status == 'PENDING_APPROVAL'
            ? AuthFailureReason.pendingApproval
            : AuthFailureReason.accountInactive,
      );
    }

    final roles = me.roles.map(AppRole.fromApi).nonNulls.toList();
    if (roles.isEmpty) {
      throw const AuthFailure(AuthFailureReason.noSupportedRole);
    }

    return Session(
      accountId: me.accountId,
      displayName: me.displayName,
      roles: roles,
      activeRole: roles.contains(AppRole.client) ? AppRole.client : roles.first,
      staffProfileId: me.staffProfileId,
      profiles: [
        for (final p in me.profiles)
          Profile(id: p.id, displayName: p.displayName, isChild: p.isChild),
      ],
      activeProfileId: me.activeProfileId,
    );
  }

  static AuthFailureReason _reasonFor(FirebaseAuthException e) {
    return switch (e.code) {
      'invalid-credential' ||
      'invalid-email' ||
      'user-not-found' ||
      'wrong-password' => AuthFailureReason.invalidCredentials,
      'user-disabled' => AuthFailureReason.accountInactive,
      'too-many-requests' => AuthFailureReason.tooManyAttempts,
      'network-request-failed' => AuthFailureReason.network,
      _ => AuthFailureReason.unknown,
    };
  }
}

/// Overridden in tests, which run without Firebase.
final firebaseAuthProvider = Provider<FirebaseAuth>(
  (ref) => FirebaseAuth.instance,
);
