import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// Events of the signed-in trainer (`OwnScheduleController` in the backend).
const ownScheduleEventsPath = '/api/v1/me/schedule-events';

/// Kind of a calendar event (`EventKind` in the backend).
enum EventKind {
  groupClass,
  personalTraining,
  workshop,
  consultation,
  block;

  static EventKind fromCode(String code) => switch (code) {
    'CLASS' => groupClass,
    'PERSONAL_TRAINING' => personalTraining,
    'WORKSHOP' => workshop,
    'CONSULTATION' => consultation,
    'BLOCK' => block,
    _ => throw FormatException('Unknown event kind: $code'),
  };
}

/// Status of a calendar event (`EventStatus` in the backend).
enum EventStatus {
  scheduled,
  completed,
  cancelled;

  static EventStatus fromCode(String code) => switch (code) {
    'SCHEDULED' => scheduled,
    'COMPLETED' => completed,
    'CANCELLED' => cancelled,
    _ => throw FormatException('Unknown event status: $code'),
  };
}

/// A staff member assigned to an event.
class EventStaff {
  const EventStaff({
    required this.staffId,
    required this.displayName,
    required this.isLead,
  });

  factory EventStaff.fromJson(Map<String, dynamic> json) {
    return EventStaff(
      staffId: json['staffId'] as String,
      displayName: json['displayName'] as String?,
      isLead: json['role'] == 'LEAD',
    );
  }

  final String staffId;
  final String? displayName;

  /// Runs the event; otherwise assists the person who runs it.
  final bool isLead;
}

/// An event in the studio's schedule.
class ScheduleEvent {
  const ScheduleEvent({
    required this.id,
    required this.kind,
    required this.status,
    required this.title,
    required this.serviceTypeName,
    required this.startsAt,
    required this.endsAt,
    required this.capacity,
    required this.staff,
    required this.roomNames,
  });

  factory ScheduleEvent.fromJson(Map<String, dynamic> json) {
    return ScheduleEvent(
      id: json['id'] as String,
      kind: EventKind.fromCode(json['kind'] as String),
      status: EventStatus.fromCode(json['status'] as String),
      title: json['title'] as String?,
      serviceTypeName: json['serviceTypeName'] as String?,
      startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
      endsAt: DateTime.parse(json['endsAt'] as String).toLocal(),
      capacity: json['capacity'] as int?,
      staff: [
        for (final staff in json['staff'] as List)
          EventStaff.fromJson(staff as Map<String, dynamic>),
      ],
      roomNames: [
        for (final resource in json['resources'] as List)
          (resource as Map<String, dynamic>)['name'] as String,
      ],
    );
  }

  final String id;
  final EventKind kind;
  final EventStatus status;
  final String? title;
  final String? serviceTypeName;

  /// Start in the device's local time.
  final DateTime startsAt;

  /// End in the device's local time, exclusive.
  final DateTime endsAt;

  /// Maximum number of participants, or `null` without a limit.
  final int? capacity;
  final List<EventStaff> staff;
  final List<String> roomNames;

  /// The event's own title, else the name of its service type.
  String? get name => title ?? serviceTypeName;
}

/// A time window of the schedule, `[from, to)` in local time.
typedef ScheduleWindow = ({DateTime from, DateTime to});

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  /// Events of the signed-in trainer that overlap [window], earliest first.
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ownScheduleEventsPath,
        queryParameters: {
          'from': window.from.toUtc().toIso8601String(),
          'to': window.to.toUtc().toIso8601String(),
        },
      );
      return [
        for (final item in response.data!['items'] as List)
          ScheduleEvent.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final scheduleApiProvider = Provider<ScheduleApi>(
  (ref) => ScheduleApi(ref.watch(dioProvider)),
);

/// The signed-in trainer's events in a window. The account id keeps one
/// account's schedule from being shown to another account.
final ownScheduleProvider = FutureProvider.autoDispose
    .family<List<ScheduleEvent>, ({String accountId, ScheduleWindow window})>(
      (ref, query) =>
          ref.watch(scheduleApiProvider).fetchOwnEvents(query.window),
    );
