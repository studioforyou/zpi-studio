import 'package:mobile/core/auth/app_role.dart';

class Profile {
  const Profile({
    required this.id,
    required this.displayName,
    required this.isChild,
  });

  /// The id of a customer profile.
  final String id;
  final String displayName;

  /// Whether this is a child managed by the account (a guardian), not the
  /// account's own customer profile.
  final bool isChild;
}

/// One tile of the role picker: a role and, for the client role, the customer
/// profile the app acts for.
class SessionChoice {
  const SessionChoice({required this.role, this.profile});

  final AppRole role;

  /// `null` for the trainer role, and for a client without a customer
  /// profile.
  final Profile? profile;

  /// Identifies the choice when it is stored, e.g. `CLIENT:<profile id>` or
  /// `TRAINER`.
  String get key => switch (profile) {
    final profile? => '${role.apiValue}:${profile.id}',
    null => role.apiValue,
  };

  @override
  bool operator ==(Object other) =>
      other is SessionChoice &&
      other.role == role &&
      other.profile?.id == profile?.id;

  @override
  int get hashCode => Object.hash(role, profile?.id);
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

  /// What the account can use the app as: each customer profile as a client
  /// (own profile first, as the backend sends them), then the trainer role.
  /// One entry means there is nothing to choose.
  List<SessionChoice> get choices => [
    if (roles.contains(AppRole.client))
      if (profiles.isEmpty)
        const SessionChoice(role: AppRole.client)
      else
        for (final profile in profiles)
          SessionChoice(role: AppRole.client, profile: profile),
    if (roles.contains(AppRole.trainer))
      const SessionChoice(role: AppRole.trainer),
  ];

  /// The choice with the given [SessionChoice.key], or `null` when the
  /// account no longer has it.
  SessionChoice? choiceByKey(String key) =>
      choices.where((choice) => choice.key == key).firstOrNull;

  /// The choice matching [activeRole] and, for a client, [activeProfileId].
  SessionChoice get activeChoice => choices.firstWhere(
    (choice) =>
        choice.role == activeRole &&
        (choice.profile == null || choice.profile!.id == activeProfileId),
    orElse: () => SessionChoice(role: activeRole),
  );

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
