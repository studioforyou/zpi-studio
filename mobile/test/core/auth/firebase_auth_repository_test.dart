import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/firebase_auth_repository.dart';
import 'package:mobile/core/auth/me_api.dart';

class _FakeUserCredential extends Fake implements UserCredential {}

/// Accepts one e-mail/password pair and records sign-outs.
class _FakeFirebaseAuth extends Fake implements FirebaseAuth {
  _FakeFirebaseAuth({this.errorCode});

  /// When set, signing in fails with this Firebase error code.
  final String? errorCode;
  bool signedIn = false;

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
    'profiles': [
      {'id': 'profile-1', 'displayName': 'Anna Kowalska'},
      {'id': 'child-1', 'displayName': 'Jan Kowalski'},
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
      expect(session.profiles.map((p) => p.id), ['profile-1', 'child-1']);
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
}
