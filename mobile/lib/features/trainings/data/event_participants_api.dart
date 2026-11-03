import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// Account is part of the cache key, so one trainer's roster is never shown
/// to another account.
typedef EventParticipantsQuery = ({String accountId, String eventId});

/// A participant with an active reservation for one of the signed-in
/// trainer's events (`OwnEventParticipantController` in the backend).
class EventParticipant {
  const EventParticipant({
    required this.reservationId,
    required this.customerId,
    required this.displayName,
    required this.isChild,
    required this.birthDate,
    required this.bookedAt,
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

class EventParticipantsApi {
  const EventParticipantsApi(this._dio);

  final Dio _dio;

  /// Participants of the event, sorted by last and first name.
  Future<List<EventParticipant>> fetch(String eventId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '$ownScheduleEventsPath/$eventId/participants',
      );
      return [
        for (final item in response.data!['items'] as List)
          EventParticipant.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final eventParticipantsApiProvider = Provider<EventParticipantsApi>(
  (ref) => EventParticipantsApi(ref.watch(dioProvider)),
);

final eventParticipantsProvider = FutureProvider.autoDispose
    .family<List<EventParticipant>, EventParticipantsQuery>(
      (ref, query) =>
          ref.watch(eventParticipantsApiProvider).fetch(query.eventId),
    );
