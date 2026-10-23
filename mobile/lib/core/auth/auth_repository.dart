import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/firebase_auth_repository.dart';
import 'package:mobile/core/auth/me_api.dart';
import 'package:mobile/core/auth/session.dart';

abstract interface class AuthRepository {
  /// Throws `AuthFailure` when signing in fails or the account cannot use
  /// the app.
  Future<Session> login({required String email, required String password});

  /// Returns the session of the user still signed in from an earlier run, or
  /// `null` when there is none or the account can no longer use the app.
  Future<Session?> restore();

  Future<void> logout();
}

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => FirebaseAuthRepository(
    ref.watch(firebaseAuthProvider),
    ref.watch(meApiProvider),
  ),
);
