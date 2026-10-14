import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/auth/session.dart';

abstract interface class AuthRepository {
  Future<Session> login({required String email, required String password});

  Future<void> logout();
}

// TODO(auth): replace FakeAuthRepository with the real implementation once the backend auth contract is agreed
final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => FakeAuthRepository(),
);
