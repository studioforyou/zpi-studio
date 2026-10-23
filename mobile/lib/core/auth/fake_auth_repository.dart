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
      staffProfileId: 'demo-staff',
      profiles: [
        Profile(id: 'self', displayName: 'Anna Kowalska', isChild: false),
        Profile(id: 'child-1', displayName: 'Jan Kowalski', isChild: true),
        Profile(id: 'child-2', displayName: 'Maria Kowalska', isChild: true),
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
