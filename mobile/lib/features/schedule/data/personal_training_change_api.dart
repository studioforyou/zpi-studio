import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// A new time and room of a personal training the trainer runs, agreed with
/// the client. The training's other details stay as they are.
class PersonalTrainingMove {
  const PersonalTrainingMove({
    required this.version,
    required this.roomId,
    required this.startsAt,
    required this.endsAt,
  });

  /// The training's version the move is based on.
  final int version;
  final String roomId;

  /// Start in the device's local time.
  final DateTime startsAt;

  /// End in the device's local time.
  final DateTime endsAt;

  Map<String, dynamic> toJson() => {
    'version': version,
    'startsAt': startsAt.toUtc().toIso8601String(),
    'endsAt': endsAt.toUtc().toIso8601String(),
    'resources': [
      {'resourceId': roomId, 'blocksWholeRoom': true},
    ],
  };
}

/// Moves the signed-in trainer's personal trainings (`OwnScheduleController`).
class PersonalTrainingChangeApi {
  const PersonalTrainingChangeApi(this._dio);

  final Dio _dio;

  /// The training at its new time and its problems, without saving anything.
  Future<PlanPreview> preview(String eventId, PersonalTrainingMove move) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '$ownScheduleEventsPath/$eventId/change-preview',
        data: move.toJson(),
      );
      return PlanPreview.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }

  /// Saves the new time and room; the client is told about the change.
  Future<ScheduleEvent> change(
    String eventId,
    PersonalTrainingMove move,
  ) async {
    try {
      final response = await _dio.put<Map<String, dynamic>>(
        '$ownScheduleEventsPath/$eventId',
        data: move.toJson(),
      );
      return ScheduleEvent.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }
}

final personalTrainingChangeApiProvider = Provider<PersonalTrainingChangeApi>(
  (ref) => PersonalTrainingChangeApi(ref.watch(dioProvider)),
);
