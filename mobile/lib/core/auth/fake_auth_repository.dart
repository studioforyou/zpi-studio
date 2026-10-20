import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/session.dart';

/// Test fake: accepts any credentials and returns a fixed demo
/// session (both roles, two child profiles).
class FakeAuthRepository implements AuthRepository {
  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async {
    return const Session(
      accountId: 'demo-user',
      displayName: 'Anna Kowalska',
      roles: [AppRole.client, AppRole.trainer],
      activeRole: AppRole.client,
      profiles: [
        Profile('self', 'Anna Kowalska'),
        Profile('child-1', 'Jan Kowalski'),
        Profile('child-2', 'Maria Kowalska'),
      ],
      activeProfileId: 'self',
    );
  }

  /// Starts signed out, like a fresh install.
  @override
  Future<Session?> restore() async => null;

  @override
  Future<void> logout() async {}
}
