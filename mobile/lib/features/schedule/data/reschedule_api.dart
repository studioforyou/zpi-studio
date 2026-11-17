import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// A new time, and optionally another room, of one occurrence the signed-in
/// trainer leads. Its title, capacity and staff stay as they are.
class ReschedulePlan {
  const ReschedulePlan({
    required this.version,
    required this.startsAt,
    required this.endsAt,
    this.roomId,
  });

  /// The event's version the change is based on.
  final int version;
  final DateTime startsAt;
  final DateTime endsAt;

  /// The room to move to, or `null` to keep the event's rooms.
  final String? roomId;

  Map<String, dynamic> toJson() => {
    'version': version,
    'startsAt': startsAt.toUtc().toIso8601String(),
    'endsAt': endsAt.toUtc().toIso8601String(),
    'resourceId': roomId,
  };
}

class RescheduleApi {
  const RescheduleApi(this._dio);

  final Dio _dio;

  /// The moved occurrence and its problems, without saving anything.
  Future<PlanPreview> preview(String eventId, ReschedulePlan plan) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '$ownScheduleEventsPath/$eventId/reschedule-preview',
        data: plan.toJson(),
      );
      return PlanPreview.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }

  /// Moves the event and returns it as saved.
  Future<ScheduleEvent> reschedule(String eventId, ReschedulePlan plan) async {
    try {
      final response = await _dio.put<Map<String, dynamic>>(
        '$ownScheduleEventsPath/$eventId/reschedule',
        data: plan.toJson(),
      );
      return ScheduleEvent.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }
}

final rescheduleApiProvider = Provider<RescheduleApi>(
  (ref) => RescheduleApi(ref.watch(dioProvider)),
);
