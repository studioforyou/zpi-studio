import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

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
    );
  }

  final String id;
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
  }) => _send(
    () => _dio.post<Map<String, dynamic>>(
      ownTimeOffPath,
      data: _body(kind, startsAt, endsAt, reason),
    ),
  );

  /// Changes a request that still waits for a decision.
  Future<TimeOff> update(
    String id, {
    required TimeOffKind kind,
    required DateTime startsAt,
    required DateTime endsAt,
    required String reason,
  }) => _send(
    () => _dio.patch<Map<String, dynamic>>(
      '$ownTimeOffPath/$id',
      data: _body(kind, startsAt, endsAt, reason),
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
}

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
