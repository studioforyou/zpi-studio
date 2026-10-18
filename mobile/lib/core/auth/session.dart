import 'package:mobile/core/auth/app_role.dart';

class Profile {
  const Profile({
    required this.id,
    required this.displayName,
    required this.isChild,
  });

  /// Parses one entry of `account.profiles` from the login response.
  factory Profile.fromJson(Object? json) {
    if (json case {
      'id': String id,
      'displayName': String displayName,
      'isChild': bool isChild,
    }) {
      return Profile(id: id, displayName: displayName, isChild: isChild);
    }
    throw FormatException('Invalid customer profile: $json');
  }

  /// The id of a customer profile.
  final String id;
  final String displayName;

  /// Whether this is a child managed by the account (a guardian), not the
  /// account's own customer profile.
  final bool isChild;
}

/// The signed-in account's session.
///
/// An account is a login ([accountId]) and can have several roles. A
/// customer profile is a participant in classes: an adult with their own
/// account, or a child without an account managed by a guardian. A staff
/// profile is a trainer. A trainer-only account may have no customer profile.
/// Reservations, passes and consents belong to a customer profile, never to
/// the account directly.
class Session {
  const Session({
    required this.accountId,
    required this.displayName,
    required this.roles,
    required this.activeRole,
    required this.staffProfileId,
    required this.profiles,
    required this.activeProfileId,
  });

  /// Parses the `account` object of the login response
  /// (`POST /api/v1/auth/login`). Roles without a mobile app are skipped; the
  /// client role is active first when the account has it.
  factory Session.fromJson(Object? json) {
    if (json case {
      'accountId': String accountId,
      'displayName': String displayName,
      'roles': List<Object?> roleValues,
      'staffProfileId': String? staffProfileId,
      'profiles': List<Object?> profileValues,
      'activeProfileId': String? activeProfileId,
    }) {
      final roles = [
        for (final value in roleValues)
          if (value is String) ?AppRole.fromApi(value),
      ];
      if (roles.isEmpty) {
        throw FormatException('Account has no mobile role: $roleValues');
      }
      return Session(
        accountId: accountId,
        displayName: displayName,
        roles: roles,
        activeRole: roles.contains(AppRole.client)
            ? AppRole.client
            : roles.first,
        staffProfileId: staffProfileId,
        profiles: [for (final value in profileValues) Profile.fromJson(value)],
        activeProfileId: activeProfileId,
      );
    }
    throw FormatException('Invalid account: $json');
  }

  final String accountId;
  final String displayName;
  final List<AppRole> roles;
  final AppRole activeRole;

  /// The id of the account's staff (trainer) profile, or `null` when the
  /// account has none.
  final String? staffProfileId;

  /// All customer profiles the account can act for: the account's own
  /// customer profile (if it has one) plus the children it is an active
  /// guardian of. May be empty.
  final List<Profile> profiles;

  /// The id of the customer profile the app currently acts for, or `null`
  /// when the account has no customer profile (e.g. a trainer-only account).
  /// The backend decides the initial value (the account's own customer
  /// profile). Ids are backend UUIDs kept as `String`.
  final String? activeProfileId;

  Session copyWith({AppRole? activeRole, String? activeProfileId}) {
    return Session(
      accountId: accountId,
      displayName: displayName,
      roles: roles,
      activeRole: activeRole ?? this.activeRole,
      staffProfileId: staffProfileId,
      profiles: profiles,
      activeProfileId: activeProfileId ?? this.activeProfileId,
    );
  }
}
