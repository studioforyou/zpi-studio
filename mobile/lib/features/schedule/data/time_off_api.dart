import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';

/// Time off of the signed-in trainer (`OwnTimeOffController` in the backend).
const ownTimeOffPath = '/api/v1/me/time-off';

/// Why the trainer is away (`TimeOffKind` in the backend).
enum TimeOffKind {
  vacation('VACATION'),
  sickLeave('SICK_LEAVE'),
  unavailable('UNAVAILABLE');

  const TimeOffKind(this.code);

  final String code;

  static TimeOffKind fromCode(String code) => values.firstWhere(
    (kind) => kind.code == code,
    orElse: () => throw FormatException('Unknown time-off kind: $code'),
  );
}

/// Where a request stands (`TimeOffStatus` in the backend).
enum TimeOffStatus {
  requested('REQUESTED'),
  approved('APPROVED'),
  rejected('REJECTED'),
  cancelled('CANCELLED');

  const TimeOffStatus(this.code);

  final String code;

  static TimeOffStatus fromCode(String code) => values.firstWhere(
    (status) => status.code == code,
    orElse: () => throw FormatException('Unknown time-off status: $code'),
  );
}

/// Leave or unavailability of the trainer.
class TimeOff {
  const TimeOff({
    required this.id,
    required this.kind,
    required this.status,
    required this.startsAt,
    required this.endsAt,
    required this.reason,
    required this.requestedAt,
    required this.decidedAt,
    required this.decisionNote,
    required this.decidedByName,
    this.version = 0,
  });

  factory TimeOff.fromJson(Map<String, dynamic> json) {
    DateTime? optional(String key) => json[key] == null
        ? null
        : DateTime.parse(json[key] as String).toLocal();
    return TimeOff(
      id: json['id'] as String,
      kind: TimeOffKind.fromCode(json['kind'] as String),
      status: TimeOffStatus.fromCode(json['status'] as String),
      startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
      endsAt: DateTime.parse(json['endsAt'] as String).toLocal(),
      reason: json['reason'] as String?,
      requestedAt: DateTime.parse(json['requestedAt'] as String).toLocal(),
      decidedAt: optional('decidedAt'),
      decisionNote: json['decisionNote'] as String?,
      decidedByName: json['decidedByName'] as String?,
      version: json['version'] as int? ?? 0,
    );
  }

  final String id;
  final int version;
  final TimeOffKind kind;
  final TimeOffStatus status;

  /// Start in the device's local time.
  final DateTime startsAt;

  /// End in the device's local time, exclusive.
  final DateTime endsAt;
  final String? reason;
  final DateTime requestedAt;

  /// When the studio approved or rejected it.
  final DateTime? decidedAt;

  /// The administrator's note on the decision.
  final String? decisionNote;

  /// Who approved or rejected it.
  final String? decidedByName;

  /// Starts and ends at midnight, so it covers whole days.
  bool get isWholeDays =>
      startsAt.hour == 0 &&
      startsAt.minute == 0 &&
      endsAt.hour == 0 &&
      endsAt.minute == 0;

  /// Requested or approved: shown in the calendar and blocks the schedule.
  bool get isActive =>
      status == TimeOffStatus.requested || status == TimeOffStatus.approved;

  /// Requested or approved time off that has not ended can still be cancelled.
  bool isCancellable(DateTime now) => isActive && endsAt.isAfter(now);
}

/// An error of the time-off endpoints with the backend's error code, e.g.
/// `TIME_OFF_OVERLAP` or `TIME_OFF_ALREADY_DECIDED`.
class TimeOffApiException extends ApiException {
  const TimeOffApiException({
    super.statusCode,
    required super.message,
    this.code,
  });

  factory TimeOffApiException.fromDio(DioException e) {
    final base = ApiException.fromDio(e);
    final data = e.response?.data;
    return TimeOffApiException(
      statusCode: base.statusCode,
      message: base.message,
      code: data is Map && data['code'] is String
          ? data['code'] as String
          : null,
    );
  }

  final String? code;
}

class TimeOffApi {
  const TimeOffApi(this._dio);

  final Dio _dio;

  /// All of the trainer's time off, the latest start first.
  Future<List<TimeOff>> fetchOwn() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(ownTimeOffPath);
      return [
        for (final item in response.data!['items'] as List)
          TimeOff.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Sends a request that waits for an administrator's decision.
  Future<TimeOff> request({
    required TimeOffKind kind,
    required DateTime startsAt,
    required DateTime endsAt,
    required String reason,
    List<String>? substitutionEventIds,
  }) => _send(
    () => _dio.post<Map<String, dynamic>>(
      ownTimeOffPath,
      data: {
        ..._body(kind, startsAt, endsAt, reason),
        'substitutionEventIds': ?substitutionEventIds,
      },
    ),
  );

  /// Changes a request that still waits for a decision.
  Future<TimeOff> update(
    String id, {
    required TimeOffKind kind,
    required DateTime startsAt,
    required DateTime endsAt,
    required String reason,
    List<String>? substitutionEventIds,
    int? version,
  }) => _send(
    () => _dio.patch<Map<String, dynamic>>(
      '$ownTimeOffPath/$id',
      data: {
        ..._body(kind, startsAt, endsAt, reason),
        'substitutionEventIds': ?substitutionEventIds,
        'version': ?version,
      },
    ),
  );

  /// Withdraws a request or cancels approved time off.
  Future<TimeOff> cancel(String id) => _send(
    () => _dio.post<Map<String, dynamic>>('$ownTimeOffPath/$id/cancel'),
  );

  static Map<String, Object?> _body(
    TimeOffKind kind,
    DateTime startsAt,
    DateTime endsAt,
    String reason,
  ) {
    final trimmed = reason.trim();
    return {
      'kind': kind.code,
      'startsAt': startsAt.toUtc().toIso8601String(),
      'endsAt': endsAt.toUtc().toIso8601String(),
      'reason': trimmed.isEmpty ? null : trimmed,
    };
  }

  Future<TimeOff> _send(
    Future<Response<Map<String, dynamic>>> Function() call,
  ) async {
    try {
      final response = await call();
      return TimeOff.fromJson(response.data!);
    } on DioException catch (e) {
      throw TimeOffApiException.fromDio(e);
    }
  }

  Future<TimeOffSubstitutionPlan> previewSubstitutions(
    DateTime from,
    DateTime to, {
    String? timeOffId,
  }) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '$ownTimeOffPath/substitution-options',
        queryParameters: {
          'from': from.toUtc().toIso8601String(),
          'to': to.toUtc().toIso8601String(),
          'timeOffId': ?timeOffId,
        },
      );
      return TimeOffSubstitutionPlan.fromJson(response.data!);
    } on DioException catch (e) {
      throw TimeOffApiException.fromDio(e);
    }
  }

  Future<TimeOffSubstitutionPlan> substitutionPlan(String id) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '$ownTimeOffPath/$id/substitutions',
      );
      return TimeOffSubstitutionPlan.fromJson(response.data!);
    } on DioException catch (e) {
      throw TimeOffApiException.fromDio(e);
    }
  }
}

class TimeOffSubstitutionPlan {
  const TimeOffSubstitutionPlan({
    required this.version,
    required this.status,
    required this.items,
  });
  factory TimeOffSubstitutionPlan.fromJson(Map<String, dynamic> json) =>
      TimeOffSubstitutionPlan(
        version: json['version'] as int,
        status: json['status'] as String,
        items: [
          for (final item in json['items'] as List)
            TimeOffLesson.fromJson(item as Map<String, dynamic>),
        ],
      );
  final int version;
  final String status;
  final List<TimeOffLesson> items;
}

class TimeOffLesson {
  const TimeOffLesson({
    required this.eventId,
    required this.title,
    required this.rooms,
    required this.startsAt,
    required this.endsAt,
    required this.selectable,
    required this.selected,
    required this.status,
  });
  factory TimeOffLesson.fromJson(Map<String, dynamic> json) => TimeOffLesson(
    eventId: json['eventId'] as String,
    title: json['title'] as String,
    rooms: json['rooms'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
    endsAt: DateTime.parse(json['endsAt'] as String).toLocal(),
    selectable: json['selectable'] as bool,
    selected: json['selected'] as bool,
    status: json['status'] as String,
  );
  final String eventId, title, rooms, status;
  final DateTime startsAt, endsAt;
  final bool selectable, selected;
}

typedef TimeOffOptionsQuery = ({
  String accountId,
  DateTime from,
  DateTime to,
  String? timeOffId,
});
final timeOffSubstitutionOptionsProvider = FutureProvider.autoDispose
    .family<TimeOffSubstitutionPlan, TimeOffOptionsQuery>((ref, query) {
      final auth = ref.watch(authControllerProvider);
      if (auth is! Authenticated || auth.session.accountId != query.accountId) {
        throw const ApiException(statusCode: 403, message: 'Account changed.');
      }
      return ref
          .watch(timeOffApiProvider)
          .previewSubstitutions(
            query.from,
            query.to,
            timeOffId: query.timeOffId,
          );
    });

final timeOffSubstitutionPlanProvider = FutureProvider.autoDispose
    .family<TimeOffSubstitutionPlan, ({String accountId, String id})>((
      ref,
      query,
    ) {
      final auth = ref.watch(authControllerProvider);
      if (auth is! Authenticated || auth.session.accountId != query.accountId) {
        throw const ApiException(statusCode: 403, message: 'Account changed.');
      }
      return ref.watch(timeOffApiProvider).substitutionPlan(query.id);
    });

final timeOffApiProvider = Provider<TimeOffApi>(
  (ref) => TimeOffApi(ref.watch(dioProvider)),
);

/// The signed-in trainer's time off. The account id keeps one account's list
/// from being shown to another account.
final ownTimeOffProvider = FutureProvider.autoDispose
    .family<List<TimeOff>, String>(
      (ref, accountId) => ref.watch(timeOffApiProvider).fetchOwn(),
      retry: (_, _) => null,
    );
