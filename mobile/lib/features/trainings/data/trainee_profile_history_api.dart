import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';

/// What part of the trainee's profile a change is about.
enum ProfileHistoryGroup {
  personal('PERSONAL'),
  card('CARD'),
  health('HEALTH');

  const ProfileHistoryGroup(this.code);

  /// Backend code.
  final String code;

  static ProfileHistoryGroup fromJson(String value) =>
      values.firstWhere((group) => group.code == value, orElse: () => personal);
}

/// Who the author of a change is to the trainee.
enum ProfileHistoryRole {
  client('CLIENT'),
  guardian('GUARDIAN'),
  trainer('TRAINER'),
  admin('ADMIN');

  const ProfileHistoryRole(this.code);

  final String code;

  static ProfileHistoryRole fromJson(String value) =>
      values.firstWhere((role) => role.code == value, orElse: () => admin);
}

/// A changed field with its old and new value; dates come as ISO dates.
class ProfileFieldChange {
  const ProfileFieldChange({
    required this.field,
    required this.oldValue,
    required this.newValue,
  });

  factory ProfileFieldChange.fromJson(Map<String, dynamic> json) =>
      ProfileFieldChange(
        field: json['field'] as String,
        oldValue: json['oldValue'] as String?,
        newValue: json['newValue'] as String?,
      );

  /// Backend field name, e.g. `phone` or `goals`.
  final String field;
  final String? oldValue;
  final String? newValue;
}

/// One change of the trainee's profile (`TraineeProfileHistory` in the
/// backend). Health entries never carry the note's text.
class ProfileHistoryEntry {
  const ProfileHistoryEntry({
    required this.id,
    required this.occurredAt,
    required this.group,
    required this.action,
    required this.actorName,
    required this.actorRole,
    required this.changes,
    required this.healthNoteKind,
  });

  factory ProfileHistoryEntry.fromJson(Map<String, dynamic> json) {
    final actor = json['actor'] as Map<String, dynamic>?;
    final kind = json['healthNoteKind'] as String?;
    return ProfileHistoryEntry(
      id: (json['id'] as num).toInt(),
      occurredAt: DateTime.parse(json['occurredAt'] as String).toLocal(),
      group: ProfileHistoryGroup.fromJson(json['group'] as String),
      action: json['action'] as String,
      actorName: actor?['name'] as String?,
      actorRole: actor == null
          ? null
          : ProfileHistoryRole.fromJson(actor['role'] as String),
      changes: [
        for (final item in json['changes'] as List)
          ProfileFieldChange.fromJson(item as Map<String, dynamic>),
      ],
      healthNoteKind: kind == null ? null : HealthNoteKind.fromJson(kind),
    );
  }

  final int id;

  /// In the device's local time.
  final DateTime occurredAt;
  final ProfileHistoryGroup group;

  /// `PROFILE_UPDATED`, `CARD_UPDATED`, `HEALTH_NOTE_ADDED` or
  /// `HEALTH_NOTE_ARCHIVED`.
  final String action;
  final String? actorName;

  /// `null` for a change made by the system.
  final ProfileHistoryRole? actorRole;
  final List<ProfileFieldChange> changes;
  final HealthNoteKind? healthNoteKind;
}

/// The trainee's change history, newest first.
class ProfileHistory {
  const ProfileHistory({
    required this.healthAccess,
    required this.entries,
    required this.hasMore,
  });

  factory ProfileHistory.fromJson(Map<String, dynamic> json) => ProfileHistory(
    healthAccess: HealthAccess.fromJson(json['healthAccess'] as String),
    entries: [
      for (final item in json['items'] as List)
        ProfileHistoryEntry.fromJson(item as Map<String, dynamic>),
    ],
    hasMore: (json['totalPages'] as num).toInt() > 1,
  );

  /// Health entries are included only with [HealthAccess.granted].
  final HealthAccess healthAccess;
  final List<ProfileHistoryEntry> entries;

  /// Whether older entries exist beyond [entries].
  final bool hasMore;
}

class TraineeProfileHistoryApi {
  const TraineeProfileHistoryApi(this._dio);
  final Dio _dio;

  /// The newest [maxEntries] changes.
  static const maxEntries = 100;

  Future<ProfileHistory> fetch(String customerId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/trainees/$customerId/profile-history',
        queryParameters: {'size': maxEntries},
      );
      return ProfileHistory.fromJson(response.data!);
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final traineeProfileHistoryApiProvider = Provider<TraineeProfileHistoryApi>(
  (ref) => TraineeProfileHistoryApi(ref.watch(dioProvider)),
);

final traineeProfileHistoryProvider = FutureProvider.autoDispose
    .family<ProfileHistory, TraineeOverviewQuery>((ref, query) {
      if (trainerOverviewScope(ref.watch(authControllerProvider)) !=
          query.scope) {
        throw const ApiException(
          statusCode: 403,
          message: 'Trainer scope changed.',
        );
      }
      return ref
          .watch(traineeProfileHistoryApiProvider)
          .fetch(query.customerId);
    });

final profileHistorySeenStorageProvider = Provider<FlutterSecureStorage>(
  (ref) => const FlutterSecureStorage(),
);

/// The newest change the trainer has seen in a trainee's history, remembered
/// on this device per account and trainee. `null` before the first visit.
class ProfileHistorySeen extends AsyncNotifier<int?> {
  ProfileHistorySeen(this.query);
  final TraineeOverviewQuery query;

  String get _storageKey =>
      'profile_history_seen.${query.scope.accountId}.${query.customerId}';

  @override
  Future<int?> build() async {
    try {
      final value = await ref
          .watch(profileHistorySeenStorageProvider)
          .read(key: _storageKey);
      return value == null ? null : int.tryParse(value);
    } catch (_) {
      // Without the stored value nothing is marked as new by mistake.
      return null;
    }
  }

  /// Remembers [entryId] as seen; older ids never replace a newer one.
  Future<void> markSeen(int entryId) async {
    final current = state.value;
    if (current != null && current >= entryId) return;
    state = AsyncData(entryId);
    try {
      await ref
          .read(profileHistorySeenStorageProvider)
          .write(key: _storageKey, value: '$entryId');
    } catch (_) {
      // The badge comes back after a restart; nothing else depends on it.
    }
  }
}

final profileHistorySeenProvider =
    AsyncNotifierProvider.family<
      ProfileHistorySeen,
      int?,
      TraineeOverviewQuery
    >(ProfileHistorySeen.new);

/// How many entries are newer than the last one seen. Before the first visit
/// there is nothing to compare with, so nothing is new.
int unseenCount(ProfileHistory history, int? seenId) => seenId == null
    ? 0
    : history.entries.where((entry) => entry.id > seenId).length;
