import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// Account is part of the cache key, so one trainer's roster is never shown
/// to another account.
typedef EventParticipantsQuery = ({String accountId, String eventId});

/// A participant's attendance at an event (`reservation.attendance`).
enum Attendance {
  /// Not recorded yet.
  unmarked,
  present,

  /// Did not come without cancelling; the pass entry is used anyway.
  absent;

  static Attendance fromCode(String? code) => switch (code) {
    'PRESENT' => present,
    'ABSENT' => absent,
    _ => unmarked,
  };

  String get code => name.toUpperCase();
}

/// A participant with an active reservation for one of the signed-in
/// trainer's events, with their attendance.
class EventParticipant {
  const EventParticipant({
    required this.reservationId,
    required this.customerId,
    required this.displayName,
    required this.isChild,
    required this.birthDate,
    required this.bookedAt,
    this.attendance = Attendance.unmarked,
  });

  factory EventParticipant.fromJson(Map<String, dynamic> json) {
    final birthDate = json['birthDate'] as String?;
    return EventParticipant(
      reservationId: json['reservationId'] as String,
      customerId: json['customerId'] as String,
      displayName: json['displayName'] as String,
      isChild: json['kind'] == 'CHILD',
      // A calendar date without a time zone.
      birthDate: birthDate == null ? null : DateTime.parse(birthDate),
      bookedAt: DateTime.parse(json['bookedAt'] as String).toLocal(),
      attendance: Attendance.fromCode(json['attendance'] as String?),
    );
  }

  final String reservationId;
  final String customerId;
  final String displayName;
  final bool isChild;

  /// The date of birth, or `null` if the studio does not know it.
  final DateTime? birthDate;

  /// When the place was reserved, in the device's local time.
  final DateTime bookedAt;

  final Attendance attendance;

  /// Age in whole years on [day], or `null` without a date of birth.
  int? ageOn(DateTime day) {
    final birthDate = this.birthDate;
    if (birthDate == null) return null;
    final hadBirthday =
        day.month > birthDate.month ||
        (day.month == birthDate.month && day.day >= birthDate.day);
    return day.year - birthDate.year - (hadBirthday ? 0 : 1);
  }
}

/// The attendance sheet of one of the signed-in trainer's events
/// (`OwnEventAttendanceController` in the backend).
class EventAttendance {
  const EventAttendance({
    required this.eventStatus,
    required this.markingOpensAt,
    required this.markingClosesAt,
    required this.markingOpen,
    required this.participants,
  });

  factory EventAttendance.fromJson(Map<String, dynamic> json) {
    return EventAttendance(
      eventStatus: EventStatus.fromCode(json['eventStatus'] as String),
      markingOpensAt: DateTime.parse(
        json['markingOpensAt'] as String,
      ).toLocal(),
      markingClosesAt: DateTime.parse(
        json['markingClosesAt'] as String,
      ).toLocal(),
      markingOpen: json['markingOpen'] as bool,
      participants: [
        for (final item in json['participants'] as List)
          EventParticipant.fromJson(item as Map<String, dynamic>),
      ],
    );
  }

  final EventStatus eventStatus;

  /// From when the trainer can record attendance: the event's start.
  final DateTime markingOpensAt;

  /// Until when the trainer can record and change attendance; later only an
  /// administrator corrects it.
  final DateTime markingClosesAt;

  /// Whether the trainer can record attendance and mark the event as held now.
  final bool markingOpen;

  /// Participants sorted by last and first name.
  final List<EventParticipant> participants;

  int count(Attendance attendance) => participants
      .where((participant) => participant.attendance == attendance)
      .length;
}

/// A rejected attendance request with the backend's stable reason code;
/// server text is never shown in the UI.
class AttendanceException extends ApiException {
  const AttendanceException({super.statusCode, this.code})
    : super(message: 'Attendance request failed');

  /// E.g. `ATTENDANCE_CLOSED`, or `null` without a response.
  final String? code;
}

class EventParticipantsApi {
  const EventParticipantsApi(this._dio);

  final Dio _dio;

  /// Participants of the event with their attendance, sorted by last and
  /// first name.
  Future<EventAttendance> fetchAttendance(String eventId) =>
      _request(() => _dio.get<Map<String, dynamic>>(_attendancePath(eventId)));

  /// Records the participants' attendance, all together.
  Future<EventAttendance> mark(String eventId, Map<String, Attendance> marks) =>
      _request(
        () => _dio.patch<Map<String, dynamic>>(
          _attendancePath(eventId),
          data: {
            'marks': [
              for (final MapEntry(:key, :value) in marks.entries)
                {'reservationId': key, 'attendance': value.code},
            ],
          },
        ),
      );

  /// Marks the event as held; participants without attendance become present.
  Future<EventAttendance> complete(String eventId) => _request(
    () => _dio.post<Map<String, dynamic>>(
      '$ownScheduleEventsPath/$eventId/complete',
    ),
  );

  static String _attendancePath(String eventId) =>
      '$ownScheduleEventsPath/$eventId/attendance';

  Future<EventAttendance> _request(
    Future<Response<Map<String, dynamic>>> Function() send,
  ) async {
    try {
      return EventAttendance.fromJson((await send()).data!);
    } on DioException catch (e) {
      final body = e.response?.data;
      throw AttendanceException(
        statusCode: e.response?.statusCode,
        code: body is Map ? body['code'] as String? : null,
      );
    }
  }
}

final eventParticipantsApiProvider = Provider<EventParticipantsApi>(
  (ref) => EventParticipantsApi(ref.watch(dioProvider)),
);

/// The attendance sheet of one of the trainer's events. Changes show the
/// sheet the server returns, so its order and window stay authoritative.
class EventAttendanceController extends AsyncNotifier<EventAttendance> {
  EventAttendanceController(this.query);

  final EventParticipantsQuery query;

  @override
  Future<EventAttendance> build() =>
      ref.watch(eventParticipantsApiProvider).fetchAttendance(query.eventId);

  /// Records one participant's attendance. Throws an [AttendanceException]
  /// and leaves the shown sheet as it was when it is rejected.
  Future<void> mark(String reservationId, Attendance attendance) =>
      _apply((api) => api.mark(query.eventId, {reservationId: attendance}));

  /// Marks the event as held. Throws like [mark].
  Future<void> complete() => _apply((api) => api.complete(query.eventId));

  Future<void> _apply(
    Future<EventAttendance> Function(EventParticipantsApi api) action,
  ) async {
    final sheet = await action(ref.read(eventParticipantsApiProvider));
    if (ref.mounted) {
      state = AsyncData(sheet);
    }
  }
}

final eventAttendanceProvider = AsyncNotifierProvider.autoDispose
    .family<EventAttendanceController, EventAttendance, EventParticipantsQuery>(
      EventAttendanceController.new,
    );
