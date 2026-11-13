import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// A private time and room reservation, without a customer or pass.
class ConsultationPlan {
  const ConsultationPlan({
    required this.roomId,
    required this.startsAt,
    required this.endsAt,
    this.guestName,
  });

  final String roomId;
  final DateTime startsAt;
  final DateTime endsAt;
  final String? guestName;

  Map<String, dynamic> toJson() => {
    'resourceId': roomId,
    'startsAt': startsAt.toUtc().toIso8601String(),
    'endsAt': endsAt.toUtc().toIso8601String(),
    'guestName': guestName?.trim(),
  };
}

class ConsultationApi {
  const ConsultationApi(this._dio);

  final Dio _dio;
  static const path = '$ownScheduleEventsPath/consultations';

  Future<PlanPreview> preview(ConsultationPlan plan) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '$path/preview',
        data: plan.toJson(),
      );
      return PlanPreview.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }

  Future<ScheduleEvent> create(ConsultationPlan plan) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        path,
        data: plan.toJson(),
      );
      return ScheduleEvent.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }
}

final consultationApiProvider = Provider<ConsultationApi>(
  (ref) => ConsultationApi(ref.watch(dioProvider)),
);
