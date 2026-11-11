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

/// How a one-off change makes an occurrence of a series differ from the
/// series' rule (`SeriesChange` in the backend). Each part is `null` when it is
/// as usual.
class SeriesChange {
  const SeriesChange({
    this.usualStartsAt,
    this.usualEndsAt,
    this.usualInstructors,
    this.usualRooms,
  });

  static SeriesChange? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    DateTime? time(String key) => json[key] == null
        ? null
        : DateTime.parse(json[key] as String).toLocal();
    List<String>? names(String key) =>
        (json[key] as List?)?.map((name) => name as String).toList();
    return SeriesChange(
      usualStartsAt: time('usualStartsAt'),
      usualEndsAt: time('usualEndsAt'),
      usualInstructors: names('usualInstructors'),
      usualRooms: names('usualRooms'),
    );
  }

  /// The usual start in the device's local time, when the time changed.
  final DateTime? usualStartsAt;

  /// The usual end in the device's local time, when the time changed.
  final DateTime? usualEndsAt;

  /// Who usually leads the event, when someone else leads it this once.
  final List<String>? usualInstructors;

  /// The usual rooms, when the event is in another room this once.
  final List<String>? usualRooms;

  bool get moved => usualStartsAt != null;

  /// The same change without its lead, e.g. to describe a stand-in's class.
  SeriesChange get withoutInstructors => SeriesChange(
    usualStartsAt: usualStartsAt,
    usualEndsAt: usualEndsAt,
    usualRooms: usualRooms,
  );

  /// A signature of the change, to tell whether a participant has seen it.
  String get signature => [
    usualStartsAt?.toIso8601String(),
    usualEndsAt?.toIso8601String(),
    usualInstructors?.join(','),
    usualRooms?.join(','),
  ].join('|');
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
    this.serviceTypeId,
    this.seriesChange,
  });

  factory ScheduleEvent.fromJson(Map<String, dynamic> json) {
    return ScheduleEvent(
      id: json['id'] as String,
      kind: EventKind.fromCode(json['kind'] as String),
      status: EventStatus.fromCode(json['status'] as String),
      title: json['title'] as String?,
      serviceTypeName: json['serviceTypeName'] as String?,
      serviceTypeId: json['serviceTypeId'] as String?,
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
      seriesChange: SeriesChange.fromJson(json['seriesChange']),
    );
  }

  final String id;
  final EventKind kind;
  final EventStatus status;
  final String? title;
  final String? serviceTypeName;

  /// The event's service, or `null` for an event without one, e.g. a block.
  final String? serviceTypeId;

  /// Start in the device's local time.
  final DateTime startsAt;

  /// End in the device's local time, exclusive.
  final DateTime endsAt;

  /// Maximum number of participants, or `null` without a limit.
  final int? capacity;
  final List<EventStaff> staff;
  final List<String> roomNames;

  /// How a one-off change makes this occurrence differ from its series, or
  /// `null` when it is as usual.
  final SeriesChange? seriesChange;

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

  /// An event the signed-in trainer runs or assists.
  Future<ScheduleEvent> fetchOwnEvent(String eventId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '$ownScheduleEventsPath/$eventId',
      );
      return ScheduleEvent.fromJson(response.data!);
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

/// One of the signed-in trainer's events, keyed by account like
/// [ownScheduleProvider].
final ownScheduleEventProvider = FutureProvider.autoDispose
    .family<ScheduleEvent, ({String accountId, String eventId})>(
      (ref, query) =>
          ref.watch(scheduleApiProvider).fetchOwnEvent(query.eventId),
    );
