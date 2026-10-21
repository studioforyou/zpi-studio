import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/auth/session.dart';

/// Restores [session] at start, or throws when it is `null`.
class _RestoringAuthRepository extends FakeAuthRepository {
  _RestoringAuthRepository(this.session);

  final Session? session;

  @override
  Future<Session?> restore() async => session ?? (throw Exception('offline'));
}

/// Returns a client-only session.
class _ClientOnlyAuthRepository implements AuthRepository {
  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async {
    return const Session(
      accountId: 'client-user',
      displayName: 'Client',
      roles: [AppRole.client],
      activeRole: AppRole.client,
      staffProfileId: null,
      profiles: [Profile(id: 'self', displayName: 'Client', isChild: false)],
      activeProfileId: 'self',
    );
  }

  @override
  Future<Session?> restore() async => null;

  @override
  Future<void> logout() async {}
}

/// Returns a trainer-only session without a customer profile.
class _TrainerOnlyAuthRepository implements AuthRepository {
  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async {
    return const Session(
      accountId: 'trainer-user',
      displayName: 'Trainer',
      roles: [AppRole.trainer],
      activeRole: AppRole.trainer,
      staffProfileId: 'trainer-staff',
      profiles: [],
      activeProfileId: null,
    );
  }

  @override
  Future<Session?> restore() async => null;

  @override
  Future<void> logout() async {}
}

void main() {
  late ProviderContainer container;

  ProviderContainer createContainer(AuthRepository repository) {
    return ProviderContainer(
      overrides: [authRepositoryProvider.overrideWithValue(repository)],
    );
  }

  AuthController controller() =>
      container.read(authControllerProvider.notifier);

  Session session() =>
      (container.read(authControllerProvider) as Authenticated).session;

  setUp(() => container = createContainer(FakeAuthRepository()));

  tearDown(() => container.dispose());

  test(
    'starts restoring, then is Unauthenticated without a saved session',
    () async {
      expect(container.read(authControllerProvider), isA<AuthRestoring>());

      await pumpEventQueue();

      expect(container.read(authControllerProvider), isA<Unauthenticated>());
    },
  );

  test('a saved session is restored at start', () async {
    container.dispose();
    container = createContainer(
      _RestoringAuthRepository(
        const Session(
          accountId: 'saved-user',
          displayName: 'Saved',
          roles: [AppRole.trainer],
          activeRole: AppRole.trainer,
          profiles: [],
          activeProfileId: null,
        ),
      ),
    );
    container.read(authControllerProvider);

    await pumpEventQueue();

    expect(session().accountId, 'saved-user');
  });

  test('a failing restore ends Unauthenticated', () async {
    container.dispose();
    container = createContainer(_RestoringAuthRepository(null));
    container.read(authControllerProvider);

    await pumpEventQueue();

    expect(container.read(authControllerProvider), isA<Unauthenticated>());
  });

  test('login authenticates as client with own profile active', () async {
    await controller().login('a@b.c', 'secret');

    expect(container.read(authControllerProvider), isA<Authenticated>());
    expect(session().activeRole, AppRole.client);
    expect(session().activeProfileId, 'self');
  });

  test('selectRole updates the active role', () async {
    await controller().login('a@b.c', 'secret');

    controller().selectRole(AppRole.trainer);

    expect(session().activeRole, AppRole.trainer);
  });

  test('selectRole ignores a role the user does not have', () async {
    container.dispose();
    container = createContainer(_ClientOnlyAuthRepository());
    await controller().login('a@b.c', 'secret');

    controller().selectRole(AppRole.trainer);

    expect(session().activeRole, AppRole.client);
  });

  test('selectProfile updates the profile and ignores unknown ids', () async {
    await controller().login('a@b.c', 'secret');

    controller().selectProfile('child-1');
    expect(session().activeProfileId, 'child-1');

    controller().selectProfile('unknown');
    expect(session().activeProfileId, 'child-1');
  });

  test('selectProfile is ignored without a customer profile', () async {
    container.dispose();
    container = createContainer(_TrainerOnlyAuthRepository());
    await controller().login('a@b.c', 'secret');
    final before = container.read(authControllerProvider);

    controller().selectProfile('anything');

    expect(container.read(authControllerProvider), same(before));
    expect(session().activeProfileId, isNull);
  });

  test('logout returns to Unauthenticated', () async {
    await controller().login('a@b.c', 'secret');

    await controller().logout();

    expect(container.read(authControllerProvider), isA<Unauthenticated>());
  });
}
