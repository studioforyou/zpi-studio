import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

/// What became of a booking once its training started.
enum TrainingOutcome {
  present,
  absent,

  /// The trainer has not recorded attendance yet.
  unmarked,
  cancelled,
  lateCancelled,

  /// The studio cancelled the training.
  classCancelled;

  static TrainingOutcome fromCode(String code) => switch (code) {
    'PRESENT' => present,
    'ABSENT' => absent,
    'CANCELLED' => cancelled,
    'LATE_CANCELLED' => lateCancelled,
    'CLASS_CANCELLED' => classCancelled,
    _ => unmarked,
  };
}

class TrainingHistoryEntry {
  const TrainingHistoryEntry({
    required this.reservationId,
    required this.eventId,
    required this.title,
    required this.serviceTypeName,
    required this.calendarColor,
    required this.startsAt,
    required this.endsAt,
    required this.staff,
    required this.roomNames,
    required this.outcome,
    required this.entryReturned,
    this.cancellationEffect,
  });

  factory TrainingHistoryEntry.fromJson(Map<String, dynamic> json) =>
      TrainingHistoryEntry(
        reservationId: json['reservationId'] as String,
        eventId: json['eventId'] as String,
        title: json['title'] as String?,
        serviceTypeName: json['serviceTypeName'] as String?,
        calendarColor: json['calendarColor'] as String?,
        startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
        endsAt: DateTime.parse(json['endsAt'] as String).toLocal(),
        staff: [
          for (final item in json['staff'] as List)
            EventStaff.fromJson(item as Map<String, dynamic>),
        ],
        roomNames: [
          for (final item in json['resources'] as List)
            (item as Map<String, dynamic>)['name'] as String,
        ],
        outcome: TrainingOutcome.fromCode(json['outcome'] as String),
        entryReturned: json['entryReturned'] as bool?,
        cancellationEffect: json['cancellationEffect'] as String?,
      );

  final String reservationId;
  final String eventId;
  final String? title;
  final String? serviceTypeName;
  final String? calendarColor;
  final DateTime startsAt;
  final DateTime endsAt;
  final List<EventStaff> staff;
  final List<String> roomNames;
  final TrainingOutcome outcome;

  /// Whether a cancellation returned the pass entry; null without one.
  final bool? entryReturned;
  final String? cancellationEffect;

  String? get name => title ?? serviceTypeName;
}

class TrainingHistoryApi {
  const TrainingHistoryApi(this._dio);

  final Dio _dio;

  /// Booked trainings of the participant that started in [ClassesQuery.window],
  /// latest first.
  Future<List<TrainingHistoryEntry>> fetchHistory(ClassesQuery query) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/profiles/${query.scope.profileId}/training-history',
        queryParameters: {
          'from': query.window.from.toUtc().toIso8601String(),
          'to': query.window.to.toUtc().toIso8601String(),
        },
      );
      return [
        for (final item in response.data!['items'] as List)
          TrainingHistoryEntry.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      final body = e.response?.data;
      throw ClassBookingException(
        statusCode: e.response?.statusCode,
        code: body is Map ? body['code'] as String? : null,
      );
    }
  }
}

final trainingHistoryApiProvider = Provider<TrainingHistoryApi>(
  (ref) => TrainingHistoryApi(ref.watch(dioProvider)),
);

final trainingHistoryProvider = FutureProvider.autoDispose
    .family<List<TrainingHistoryEntry>, ClassesQuery>(
      (ref, query) => ref.watch(trainingHistoryApiProvider).fetchHistory(query),
    );

typedef AccountTrainingHistoryQuery = ({
  String accountId,
  String? profileId,
  ScheduleWindow window,
});

class ParticipantTraining {
  const ParticipantTraining(this.profileId, this.profileName, this.training);
  final String profileId;
  final String profileName;
  final TrainingHistoryEntry training;
}

/// Reuses the authorized participant history, including currently represented
/// children. The account stays in the provider key to isolate late responses.
final accountTrainingHistoryProvider = FutureProvider.autoDispose
    .family<List<ParticipantTraining>, AccountTrainingHistoryQuery>((
      ref,
      query,
    ) async {
      final auth = ref.watch(authControllerProvider);
      if (passUsageAccount(auth) != query.accountId) {
        throw const ClassBookingException(statusCode: 403);
      }
      final profiles = (auth as Authenticated).session.profiles;
      if (query.profileId != null &&
          !profiles.any((profile) => profile.id == query.profileId)) {
        throw const ClassBookingException(statusCode: 404);
      }
      final api = ref.watch(trainingHistoryApiProvider);
      final results = await Future.wait([
        for (final profile in profiles)
          if (query.profileId == null || profile.id == query.profileId)
            api
                .fetchHistory((
                  scope: (accountId: query.accountId, profileId: profile.id),
                  window: query.window,
                ))
                .then(
                  (entries) => [
                    for (final entry in entries)
                      ParticipantTraining(
                        profile.id,
                        profile.displayName,
                        entry,
                      ),
                  ],
                ),
      ]);
      final entries = results.expand((items) => items).toList();
      entries.sort((a, b) {
        final date = b.training.startsAt.compareTo(a.training.startsAt);
        if (date != 0) return date;
        final participant = a.profileId.compareTo(b.profileId);
        return participant != 0
            ? participant
            : a.training.reservationId.compareTo(b.training.reservationId);
      });
      return entries;
    });
