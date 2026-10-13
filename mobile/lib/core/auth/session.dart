import 'package:mobile/core/auth/app_role.dart';

class Profile {
  const Profile(this.id, this.displayName);

  /// The id of a customer profile.
  final String id;
  final String displayName;
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
    required this.profiles,
    required this.activeProfileId,
  });

  final String accountId;
  final String displayName;
  final List<AppRole> roles;
  final AppRole activeRole;

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
      profiles: profiles,
      activeProfileId: activeProfileId ?? this.activeProfileId,
    );
  }
}
