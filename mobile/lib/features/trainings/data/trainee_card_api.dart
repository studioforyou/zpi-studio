import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';

/// Longest goals or sport experience the backend accepts, after trimming.
const traineeCardTextMaxLength = 2000;

/// Longest health note description the backend accepts, after trimming.
const healthNoteMaxLength = 1000;

/// Whether the trainer sees the trainee's health notes: the backend needs the
/// `CUSTOMER_HEALTH_READ` permission and the trainee's consent on the
/// trainer's own assignment.
enum HealthAccess {
  granted,

  /// The trainee has not shared their health data with this trainer.
  notShared,

  /// The trainer does not have the permission to read health data.
  noPermission;

  static HealthAccess fromJson(String value) => switch (value) {
    'GRANTED' => granted,
    'NOT_SHARED' => notShared,
    // An unknown value never reveals anything.
    _ => noPermission,
  };
}

enum HealthNoteKind {
  injury('INJURY'),
  condition('CONDITION'),
  contraindication('CONTRAINDICATION'),
  other('OTHER');

  const HealthNoteKind(this.code);

  /// Backend code.
  final String code;

  static HealthNoteKind fromJson(String value) =>
      values.firstWhere((kind) => kind.code == value, orElse: () => other);
}

/// A health restriction or injury. Notes are never edited: one that no longer
/// applies is archived and stays as history.
class HealthNote {
  const HealthNote({
    required this.id,
    required this.kind,
    required this.description,
    required this.reportedAt,
    required this.reportedBy,
    required this.archivedAt,
    required this.archivedBy,
  });

  factory HealthNote.fromJson(Map<String, dynamic> json) => HealthNote(
    id: json['id'] as String,
    kind: HealthNoteKind.fromJson(json['kind'] as String),
    description: json['description'] as String,
    reportedAt: DateTime.parse(json['reportedAt'] as String).toLocal(),
    reportedBy: json['reportedBy'] as String?,
    archivedAt: switch (json['archivedAt']) {
      final String value => DateTime.parse(value).toLocal(),
      _ => null,
    },
    archivedBy: json['archivedBy'] as String?,
  );

  final String id;
  final HealthNoteKind kind;
  final String description;

  /// In the device's local time, like [archivedAt].
  final DateTime reportedAt;

  /// The trainee, a guardian or a trainer; `null` when unknown.
  final String? reportedBy;
  final DateTime? archivedAt;
  final String? archivedBy;

  bool get isArchived => archivedAt != null;
}

/// The card a trainer keeps for a trainee (`TraineeCardController` in the
/// backend). One card per trainee, shared by all of their current trainers.
class TraineeCard {
  const TraineeCard({
    required this.customerId,
    required this.goals,
    required this.sportExperience,
    required this.version,
    required this.updatedAt,
    required this.updatedBy,
    required this.healthAccess,
    required this.healthNotes,
  });

  factory TraineeCard.fromJson(Map<String, dynamic> json) {
    final access = HealthAccess.fromJson(json['healthAccess'] as String);
    final notes = json['healthNotes'] as List?;
    return TraineeCard(
      customerId: json['customerId'] as String,
      goals: json['goals'] as String?,
      sportExperience: json['sportExperience'] as String?,
      version: json['version'] as int,
      updatedAt: switch (json['updatedAt']) {
        final String value => DateTime.parse(value).toLocal(),
        _ => null,
      },
      updatedBy: json['updatedBy'] as String?,
      healthAccess: access,
      healthNotes: access == HealthAccess.granted && notes != null
          ? [
              for (final item in notes)
                HealthNote.fromJson(item as Map<String, dynamic>),
            ]
          : const [],
    );
  }

  final String customerId;
  final String? goals;
  final String? sportExperience;

  /// Sent back with an update; 0 while nobody has filled in the card.
  final int version;
  final DateTime? updatedAt;
  final String? updatedBy;
  final HealthAccess healthAccess;

  /// Current notes first, then archived ones; always empty unless
  /// [healthAccess] is [HealthAccess.granted].
  final List<HealthNote> healthNotes;

  List<HealthNote> get currentHealthNotes =>
      healthNotes.where((note) => !note.isArchived).toList();

  List<HealthNote> get archivedHealthNotes =>
      healthNotes.where((note) => note.isArchived).toList();
}

/// A rejected trainee card request, with the backend's error `code`.
class TraineeCardException implements Exception {
  const TraineeCardException({this.statusCode, this.code});

  final int? statusCode;
  final String? code;

  /// Someone changed the card since it was loaded.
  bool get isVersionConflict => code == 'VERSION_CONFLICT';

  /// The cooperation ended, or the trainee is no longer active.
  bool get isUnavailable => statusCode == 404;

  /// The trainer lost access to the trainee's health data.
  bool get isHealthAccessDenied => statusCode == 403;

  @override
  String toString() => 'TraineeCardException($statusCode, $code)';
}

class TraineeCardApi {
  const TraineeCardApi(this._dio);
  final Dio _dio;

  static String _path(String customerId) => '/api/v1/me/trainees/$customerId';

  Future<TraineeCard> fetch(String customerId) =>
      _card(() => _dio.get<Map<String, dynamic>>('${_path(customerId)}/card'));

  /// Saves both texts; an empty text clears it. [version] is the version of
  /// the card the change is based on.
  Future<TraineeCard> update(
    String customerId, {
    required String goals,
    required String sportExperience,
    required int version,
  }) => _card(
    () => _dio.patch<Map<String, dynamic>>(
      '${_path(customerId)}/card',
      data: {
        'goals': goals.trim(),
        'sportExperience': sportExperience.trim(),
        'version': version,
      },
    ),
  );

  Future<HealthNote> addHealthNote(
    String customerId, {
    required HealthNoteKind kind,
    required String description,
  }) => _note(
    () => _dio.post<Map<String, dynamic>>(
      '${_path(customerId)}/health-notes',
      data: {'kind': kind.code, 'description': description.trim()},
    ),
  );

  Future<HealthNote> archiveHealthNote(String customerId, String noteId) =>
      _note(
        () => _dio.post<Map<String, dynamic>>(
          '${_path(customerId)}/health-notes/$noteId/archive',
        ),
      );

  Future<TraineeCard> _card(
    Future<Response<Map<String, dynamic>>> Function() send,
  ) async => TraineeCard.fromJson(await _send(send));

  Future<HealthNote> _note(
    Future<Response<Map<String, dynamic>>> Function() send,
  ) async => HealthNote.fromJson(await _send(send));

  Future<Map<String, dynamic>> _send(
    Future<Response<Map<String, dynamic>>> Function() send,
  ) async {
    try {
      return (await send()).data!;
    } on DioException catch (e) {
      final body = e.response?.data;
      throw TraineeCardException(
        statusCode: e.response?.statusCode,
        code: body is Map ? body['code'] as String? : null,
      );
    }
  }
}

final traineeCardApiProvider = Provider<TraineeCardApi>(
  (ref) => TraineeCardApi(ref.watch(dioProvider)),
);

final traineeCardProvider = FutureProvider.autoDispose
    .family<TraineeCard, TraineeOverviewQuery>((ref, query) {
      if (trainerOverviewScope(ref.watch(authControllerProvider)) !=
          query.scope) {
        throw const TraineeCardException(statusCode: 403);
      }
      return ref.watch(traineeCardApiProvider).fetch(query.customerId);
    });
