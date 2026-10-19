import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/session.dart';

void main() {
  Map<String, Object?> account({required List<String> roles}) => {
    'accountId': 'a1',
    'email': 'anna@example.com',
    'displayName': 'Anna Kowalska',
    'roles': roles,
    'staffProfileId': null,
    'profiles': [
      {'id': 'p1', 'displayName': 'Anna Kowalska', 'isChild': false},
      {'id': 'p2', 'displayName': 'Jan Kowalski', 'isChild': true},
    ],
    'activeProfileId': 'p1',
  };

  test('parses the account of the login response', () {
    final session = Session.fromJson(account(roles: ['CLIENT', 'TRAINER']));

    expect(session.accountId, 'a1');
    expect(session.roles, [AppRole.client, AppRole.trainer]);
    expect(session.activeRole, AppRole.client);
    expect(session.staffProfileId, isNull);
    expect(session.profiles.map((p) => p.isChild), [false, true]);
    expect(session.activeProfileId, 'p1');
  });

  test('skips roles without a mobile app', () {
    final session = Session.fromJson(account(roles: ['ADMIN', 'TRAINER']));

    expect(session.roles, [AppRole.trainer]);
    expect(session.activeRole, AppRole.trainer);
  });

  test('rejects an account without a mobile role', () {
    expect(
      () => Session.fromJson(account(roles: ['ADMIN'])),
      throwsFormatException,
    );
  });
}
