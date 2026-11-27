import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';

/// The signed-in account's schedule as calendar entries
/// (`OwnCalendarEventsController` in the backend), the same as in its
/// calendar feed.
const ownCalendarEventsPath = '/api/v1/me/calendar-events';

/// One event of the account's schedule, ready for a calendar.
class CalendarEntry {
  const CalendarEntry({
    required this.uid,
    required this.role,
    required this.startsAt,
    required this.endsAt,
    required this.title,
    required this.cancelled,
    required this.sequence,
    this.location,
    this.description,
  });

  factory CalendarEntry.fromJson(Map<String, dynamic> json) => CalendarEntry(
    uid: json['uid'] as String,
    role: json['role'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String),
    endsAt: DateTime.parse(json['endsAt'] as String),
    title: json['title'] as String,
    location: json['location'] as String?,
    description: json['description'] as String?,
    cancelled: json['cancelled'] as bool,
    sequence: (json['sequence'] as num).toInt(),
  );

  /// Stays the same for the event (and participant) in every response.
  final String uid;

  /// The part of the schedule the entry belongs to: `CLIENT` or `TRAINER`,
  /// as [CalendarFeedScope.role].
  final String role;
  final DateTime startsAt;
  final DateTime endsAt;

  /// Already says whose event it is, e.g. "Zosia: Pole dance".
  final String title;
  final String? location;
  final String? description;

  /// The studio cancelled the event.
  final bool cancelled;
  final int sequence;
}

class CalendarEventsApi {
  const CalendarEventsApi(this._dio);

  final Dio _dio;

  /// Entries from 30 days ago on, limited to [scope].
  Future<List<CalendarEntry>> fetch(CalendarFeedScope scope) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ownCalendarEventsPath,
        queryParameters: {if (scope.role != null) 'role': scope.role},
      );
      return (response.data!['items'] as List<dynamic>)
          .map((item) => CalendarEntry.fromJson(item as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final calendarEventsApiProvider = Provider<CalendarEventsApi>(
  (ref) => CalendarEventsApi(ref.watch(dioProvider)),
);
