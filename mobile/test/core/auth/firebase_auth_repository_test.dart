import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/firebase_auth_repository.dart';
import 'package:mobile/core/auth/me_api.dart';

class _FakeUserCredential extends Fake implements UserCredential {}

class _FakeUser extends Fake implements User {}

/// Accepts one e-mail/password pair and records sign-outs.
class _FakeFirebaseAuth extends Fake implements FirebaseAuth {
  _FakeFirebaseAuth({this.errorCode, this.signedIn = false});

  /// When set, signing in fails with this Firebase error code.
  final String? errorCode;

  /// Whether a user is signed in, e.g. from an earlier app start.
  bool signedIn;

  @override
  Stream<User?> authStateChanges() =>
      Stream.value(signedIn ? _FakeUser() : null);

  @override
  Future<UserCredential> signInWithEmailAndPassword({
    required String email,
    required String password,
  }) async {
    final code = errorCode;
    if (code != null) {
      throw FirebaseAuthException(code: code);
    }
    signedIn = true;
    return _FakeUserCredential();
  }

  @override
  Future<void> signOut() async => signedIn = false;
}

class _FakeMeApi implements MeApi {
  _FakeMeApi(this._result);

  final Future<MeResponse> Function() _result;

  @override
  Future<MeResponse> fetchMe() => _result();
}

MeResponse _me({
  String status = 'ACTIVE',
  List<String> roles = const ['CLIENT', 'TRAINER'],
}) {
  return MeResponse.fromJson({
    'accountId': 'account-1',
    'email': 'anna@example.com',
    'status': status,
    'displayName': 'Anna Kowalska',
    'roles': roles,
    'staffProfileId': 'staff-1',
    'profiles': [
      {'id': 'profile-1', 'displayName': 'Anna Kowalska', 'isChild': false},
      {'id': 'child-1', 'displayName': 'Jan Kowalski', 'isChild': true},
    ],
    'activeProfileId': 'profile-1',
  });
}

void main() {
  Future<void> expectFailure(
    Future<void> Function() action,
    AuthFailureReason reason,
  ) {
    return expectLater(
      action,
      throwsA(isA<AuthFailure>().having((f) => f.reason, 'reason', reason)),
    );
  }

  test(
    'an active account becomes a session with the client role active',
    () async {
      final auth = _FakeFirebaseAuth();
      final repository = FirebaseAuthRepository(
        auth,
        _FakeMeApi(() async => _me()),
      );

      final session = await repository.login(
        email: ' anna@example.com ',
        password: 'secret',
      );

      expect(session.accountId, 'account-1');
      expect(session.displayName, 'Anna Kowalska');
      expect(session.roles, [AppRole.client, AppRole.trainer]);
      expect(session.activeRole, AppRole.client);
      expect(session.staffProfileId, 'staff-1');
      expect(session.profiles.map((p) => p.id), ['profile-1', 'child-1']);
      expect(session.profiles.map((p) => p.isChild), [false, true]);
      expect(session.activeProfileId, 'profile-1');
      expect(auth.signedIn, isTrue);
    },
  );

  test('roles the app does not support are dropped', () async {
    final repository = FirebaseAuthRepository(
      _FakeFirebaseAuth(),
      _FakeMeApi(() async => _me(roles: ['ADMIN', 'TRAINER'])),
    );

    final session = await repository.login(email: 'a', password: 'b');

    expect(session.roles, [AppRole.trainer]);
    expect(session.activeRole, AppRole.trainer);
  });

  test('wrong credentials are reported as such', () async {
    final repository = FirebaseAuthRepository(
      _FakeFirebaseAuth(errorCode: 'invalid-credential'),
      _FakeMeApi(() async => _me()),
    );

    await expectFailure(
      () => repository.login(email: 'a', password: 'b'),
      AuthFailureReason.invalidCredentials,
    );
  });

  for (final (status, reason) in [
    ('PENDING_APPROVAL', AuthFailureReason.pendingApproval),
    ('SUSPENDED', AuthFailureReason.accountInactive),
  ]) {
    test('a $status account is signed out again', () async {
      final auth = _FakeFirebaseAuth();
      final repository = FirebaseAuthRepository(
        auth,
        _FakeMeApi(() async => _me(status: status)),
      );

      await expectFailure(
        () => repository.login(email: 'a', password: 'b'),
        reason,
      );
      expect(auth.signedIn, isFalse);
    });
  }

  test('an account without a supported role is signed out again', () async {
    final auth = _FakeFirebaseAuth();
    final repository = FirebaseAuthRepository(
      auth,
      _FakeMeApi(() async => _me(roles: ['ADMIN'])),
    );

    await expectFailure(
      () => repository.login(email: 'a', password: 'b'),
      AuthFailureReason.noSupportedRole,
    );
    expect(auth.signedIn, isFalse);
  });

  test('a backend failure signs the user out again', () async {
    final auth = _FakeFirebaseAuth();
    final repository = FirebaseAuthRepository(
      auth,
      _FakeMeApi(
        () async => throw const AuthFailure(AuthFailureReason.accountNotFound),
      ),
    );

    await expectFailure(
      () => repository.login(email: 'a', password: 'b'),
      AuthFailureReason.accountNotFound,
    );
    expect(auth.signedIn, isFalse);
  });

  group('restore', () {
    test('without a saved user there is no session', () async {
      var asked = false;
      final repository = FirebaseAuthRepository(
        _FakeFirebaseAuth(),
        _FakeMeApi(() async {
          asked = true;
          return _me();
        }),
      );

      expect(await repository.restore(), isNull);
      expect(asked, isFalse);
    });

    test('a saved user with an active account gets a session', () async {
      final auth = _FakeFirebaseAuth(signedIn: true);
      final repository = FirebaseAuthRepository(
        auth,
        _FakeMeApi(() async => _me()),
      );

      final session = await repository.restore();

      expect(session?.accountId, 'account-1');
      expect(auth.signedIn, isTrue);
    });

    test(
      'a saved user whose account is no longer active is signed out',
      () async {
        final auth = _FakeFirebaseAuth(signedIn: true);
        final repository = FirebaseAuthRepository(
          auth,
          _FakeMeApi(() async => _me(status: 'SUSPENDED')),
        );

        expect(await repository.restore(), isNull);
        expect(auth.signedIn, isFalse);
      },
    );

    test('without a connection the saved user stays signed in', () async {
      final auth = _FakeFirebaseAuth(signedIn: true);
      final repository = FirebaseAuthRepository(
        auth,
        _FakeMeApi(
          () async => throw const AuthFailure(AuthFailureReason.network),
        ),
      );

      expect(await repository.restore(), isNull);
      expect(auth.signedIn, isTrue);
    });
  });
}
