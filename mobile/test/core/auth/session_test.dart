import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/session.dart';

const _self = Profile(id: 'self', displayName: 'Anna', isChild: false);
const _child = Profile(id: 'child', displayName: 'Jan', isChild: true);

Session _session({
  required List<AppRole> roles,
  List<Profile> profiles = const [],
  AppRole? activeRole,
  String? activeProfileId,
}) {
  return Session(
    accountId: 'account',
    displayName: 'Anna',
    roles: roles,
    activeRole: activeRole ?? roles.first,
    staffProfileId: roles.contains(AppRole.trainer) ? 'staff' : null,
    profiles: profiles,
    activeProfileId: activeProfileId,
  );
}

void main() {
  test('choices list each customer profile, then the trainer role', () {
    final session = _session(
      roles: [AppRole.client, AppRole.trainer],
      profiles: [_self, _child],
    );

    expect(session.choices, const [
      SessionChoice(role: AppRole.client, profile: _self),
      SessionChoice(role: AppRole.client, profile: _child),
      SessionChoice(role: AppRole.trainer),
    ]);
  });

  test('a trainer-only account has a single choice', () {
    final session = _session(roles: [AppRole.trainer]);

    expect(session.choices, const [SessionChoice(role: AppRole.trainer)]);
  });

  test('a client without a customer profile still has a client choice', () {
    final session = _session(roles: [AppRole.client]);

    expect(session.choices, const [SessionChoice(role: AppRole.client)]);
  });

  test('activeChoice follows the active role and profile', () {
    final session = _session(
      roles: [AppRole.client, AppRole.trainer],
      profiles: [_self, _child],
      activeProfileId: 'child',
    );

    expect(
      session.activeChoice,
      const SessionChoice(role: AppRole.client, profile: _child),
    );
    expect(
      session.copyWith(activeRole: AppRole.trainer).activeChoice,
      const SessionChoice(role: AppRole.trainer),
    );
  });
}
