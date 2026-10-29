import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// One-off classes of the signed-in trainer (`OwnScheduleController`).
const ownClassesPath = '/api/v1/me/schedule-events';

/// Recurring classes of the signed-in trainer
/// (`OwnRecurrenceSeriesController`).
const ownSeriesPath = '/api/v1/me/recurrence-series';

const serviceTypesPath = '/api/v1/service-types';
const roomsPath = '/api/v1/resources';

/// A kind of group class a trainer can add.
class PlanningServiceType {
  const PlanningServiceType({
    required this.id,
    required this.name,
    required this.defaultDurationMinutes,
    required this.maxParticipants,
  });

  factory PlanningServiceType.fromJson(Map<String, dynamic> json) {
    return PlanningServiceType(
      id: json['id'] as String,
      name: json['name'] as String,
      defaultDurationMinutes: json['defaultDurationMinutes'] as int,
      maxParticipants: json['maxParticipants'] as int?,
    );
  }

  final String id;
  final String name;
  final int defaultDurationMinutes;

  /// Usual limit of participants, or `null` without a limit.
  final int? maxParticipants;
}

/// A room a class can take place in.
class PlanningRoom {
  const PlanningRoom({
    required this.id,
    required this.name,
    required this.maxPeople,
  });

  factory PlanningRoom.fromJson(Map<String, dynamic> json) {
    return PlanningRoom(
      id: json['id'] as String,
      name: json['name'] as String,
      maxPeople: json['maxPeople'] as int?,
    );
  }

  final String id;
  final String name;
  final int? maxPeople;
}

/// A group class the trainer wants to add, run by the trainer in one room.
sealed class ClassPlan {
  const ClassPlan({
    required this.serviceTypeId,
    required this.roomId,
    required this.capacity,
  });

  final String serviceTypeId;
  final String roomId;

  /// Maximum number of participants; the service type's limit when `null`.
  final int? capacity;

  Map<String, dynamic> toJson();

  Map<String, dynamic> _commonJson() => {
    'serviceTypeId': serviceTypeId,
    'capacity': ?capacity,
    'resources': [
      {'resourceId': roomId, 'blocksWholeRoom': true},
    ],
  };
}

/// A class on one day.
class OneOffClassPlan extends ClassPlan {
  const OneOffClassPlan({
    required super.serviceTypeId,
    required super.roomId,
    super.capacity,
    required this.startsAt,
    required this.endsAt,
  });

  /// Start in the device's local time.
  final DateTime startsAt;

  /// End in the device's local time.
  final DateTime endsAt;

  @override
  Map<String, dynamic> toJson() => {
    ..._commonJson(),
    'startsAt': startsAt.toUtc().toIso8601String(),
    'endsAt': endsAt.toUtc().toIso8601String(),
  };
}

/// A class repeated every week on the given weekdays, at the same studio
/// local time.
class RecurringClassPlan extends ClassPlan {
  const RecurringClassPlan({
    required super.serviceTypeId,
    required super.roomId,
    super.capacity,
    required this.weekdays,
    required this.startTime,
    required this.durationMinutes,
    required this.startsOn,
    required this.endsOn,
    this.excludedDates = const {},
  });

  /// ISO weekdays, [DateTime.monday] to [DateTime.sunday].
  final Set<int> weekdays;
  final TimeOfDay startTime;
  final int durationMinutes;

  /// First day of the series; only the date part is used.
  final DateTime startsOn;

  /// Last day of the series; only the date part is used.
  final DateTime endsOn;

  /// Days left out of the series, e.g. ones that conflict.
  final Set<DateTime> excludedDates;

  @override
  Map<String, dynamic> toJson() => {
    ..._commonJson(),
    'weekdays': weekdays.toList()..sort(),
    'localStartTime':
        '${_twoDigits(startTime.hour)}:${_twoDigits(startTime.minute)}',
    'durationMinutes': durationMinutes,
    'startsOn': formatDate(startsOn),
    'endsOn': formatDate(endsOn),
    'excludedDates': [for (final day in excludedDates) formatDate(day)]..sort(),
  };
}

String _twoDigits(int value) => value.toString().padLeft(2, '0');

/// `yyyy-MM-dd` of the date part of [day].
String formatDate(DateTime day) =>
    '${day.year}-${_twoDigits(day.month)}-${_twoDigits(day.day)}';

/// Kind of a problem with a planned class (`IssueType` in the backend).
enum IssueType {
  roomBusy,
  trainerBusy,
  trainerOnLeave,
  trainerLeaveRequested,
  outsideWorkingHours,
  notQualified,
  unknown;

  static IssueType fromCode(String code) => switch (code) {
    'RESOURCE_BUSY' => roomBusy,
    'STAFF_BUSY' => trainerBusy,
    'STAFF_ON_LEAVE' => trainerOnLeave,
    'STAFF_LEAVE_REQUESTED' => trainerLeaveRequested,
    'STAFF_OUTSIDE_AVAILABILITY' => outsideWorkingHours,
    'STAFF_NOT_QUALIFIED' => notQualified,
    _ => unknown,
  };
}

/// A problem with a planned class.
class PlanIssue {
  const PlanIssue({
    required this.type,
    required this.blocking,
    this.eventTitle,
    this.startsAt,
    this.endsAt,
  });

  factory PlanIssue.fromJson(Map<String, dynamic> json) {
    final startsAt = json['startsAt'] as String?;
    final endsAt = json['endsAt'] as String?;
    return PlanIssue(
      type: IssueType.fromCode(json['type'] as String),
      blocking: json['blocking'] as bool,
      eventTitle: json['eventTitle'] as String?,
      startsAt: startsAt == null ? null : DateTime.parse(startsAt).toLocal(),
      endsAt: endsAt == null ? null : DateTime.parse(endsAt).toLocal(),
    );
  }

  final IssueType type;

  /// Whether the problem prevents saving.
  final bool blocking;

  /// The other event, for [IssueType.roomBusy] and [IssueType.trainerBusy].
  final String? eventTitle;

  /// Start of the other event or of the leave, in local time.
  final DateTime? startsAt;

  /// End of the other event or of the leave, in local time.
  final DateTime? endsAt;
}

/// One occurrence of a planned class and its problems.
class PlannedOccurrence {
  const PlannedOccurrence({
    required this.date,
    required this.startsAt,
    required this.endsAt,
    required this.excluded,
    required this.issues,
  });

  factory PlannedOccurrence.fromJson(Map<String, dynamic> json) {
    return PlannedOccurrence(
      date: DateTime.parse(json['date'] as String),
      startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
      endsAt: DateTime.parse(json['endsAt'] as String).toLocal(),
      excluded: json['excluded'] as bool,
      issues: [
        for (final issue in json['issues'] as List)
          PlanIssue.fromJson(issue as Map<String, dynamic>),
      ],
    );
  }

  /// Studio local date of the occurrence.
  final DateTime date;
  final DateTime startsAt;
  final DateTime endsAt;

  /// Left out of the series; no class is created for it.
  final bool excluded;
  final List<PlanIssue> issues;

  bool get hasBlockingIssue => issues.any((issue) => issue.blocking);
}

/// What adding a class would create and what stands in its way.
class PlanPreview {
  const PlanPreview({
    required this.occurrences,
    required this.warnings,
    required this.conflictCount,
  });

  factory PlanPreview.fromJson(Map<String, dynamic> json) {
    return PlanPreview(
      occurrences: [
        for (final occurrence in json['occurrences'] as List)
          PlannedOccurrence.fromJson(occurrence as Map<String, dynamic>),
      ],
      warnings: [
        for (final issue in json['warnings'] as List)
          PlanIssue.fromJson(issue as Map<String, dynamic>),
      ],
      conflictCount: json['conflictCount'] as int,
    );
  }

  final List<PlannedOccurrence> occurrences;

  /// Problems of the whole plan that do not block saving.
  final List<PlanIssue> warnings;

  /// Number of occurrences, not excluded, that cannot be saved.
  final int conflictCount;
}

/// A plan the backend refused, with its error code, e.g.
/// `SERVICE_NOT_ALLOWED_IN_ROOM`, and for `VALIDATION_FAILED` the first
/// invalid field.
class PlanRejectedException extends ApiException {
  const PlanRejectedException({
    super.statusCode,
    required super.message,
    required this.code,
    this.field,
  });

  final String? code;
  final String? field;
}

class ClassPlanningApi {
  const ClassPlanningApi(this._dio);

  final Dio _dio;

  /// Active group class types, by name.
  Future<List<PlanningServiceType>> fetchServiceTypes() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        serviceTypesPath,
        queryParameters: {'active': true},
      );
      return [
        for (final item in response.data!['items'] as List)
          if ((item as Map<String, dynamic>)['category'] == 'GROUP_CLASS')
            PlanningServiceType.fromJson(item),
      ]..sort((a, b) => a.name.compareTo(b.name));
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Active rooms, by name.
  Future<List<PlanningRoom>> fetchRooms() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        roomsPath,
        queryParameters: {'isActive': true},
      );
      return [
        for (final item in response.data!['items'] as List)
          PlanningRoom.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// The occurrences [plan] would create and their problems, without
  /// saving anything.
  Future<PlanPreview> preview(ClassPlan plan) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '${_path(plan)}/preview',
        data: plan.toJson(),
      );
      return PlanPreview.fromJson(response.data!);
    } on DioException catch (e) {
      throw _rejection(e);
    }
  }

  /// Adds [plan] to the trainer's schedule.
  Future<void> create(ClassPlan plan) async {
    try {
      await _dio.post<Map<String, dynamic>>(_path(plan), data: plan.toJson());
    } on DioException catch (e) {
      throw _rejection(e);
    }
  }

  static String _path(ClassPlan plan) => switch (plan) {
    OneOffClassPlan() => ownClassesPath,
    RecurringClassPlan() => ownSeriesPath,
  };

  static ApiException _rejection(DioException e) {
    final body = e.response?.data;
    if (body is! Map<String, dynamic>) {
      return ApiException.fromDio(e);
    }
    final errors = body['errors'];
    return PlanRejectedException(
      statusCode: e.response?.statusCode,
      message: body['detail'] as String? ?? 'Plan rejected',
      code: body['code'] as String?,
      field: errors is List && errors.isNotEmpty
          ? (errors.first as Map<String, dynamic>)['field'] as String?
          : null,
    );
  }
}

final classPlanningApiProvider = Provider<ClassPlanningApi>(
  (ref) => ClassPlanningApi(ref.watch(dioProvider)),
);

final planningServiceTypesProvider =
    FutureProvider.autoDispose<List<PlanningServiceType>>(
      (ref) => ref.watch(classPlanningApiProvider).fetchServiceTypes(),
    );

final planningRoomsProvider = FutureProvider.autoDispose<List<PlanningRoom>>(
  (ref) => ref.watch(classPlanningApiProvider).fetchRooms(),
);
