import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';

class PersonalTrainingPlan {
  const PersonalTrainingPlan({
    required this.customerId,
    required this.serviceTypeId,
    required this.roomId,
    required this.startsAt,
    required this.endsAt,
  });

  final String customerId;
  final String serviceTypeId;
  final String roomId;
  final DateTime startsAt;
  final DateTime endsAt;

  Map<String, dynamic> toJson() => {
    'customerId': customerId,
    'serviceTypeId': serviceTypeId,
    'resourceId': roomId,
    'startsAt': startsAt.toUtc().toIso8601String(),
    'endsAt': endsAt.toUtc().toIso8601String(),
  };
}

class PersonalPlanningPass {
  PersonalPlanningPass.fromJson(Map<String, dynamic> json)
    : id = json['id'] as String,
      name = json['productName'] as String,
      entriesRemaining = json['entriesRemaining'] as int?,
      validUntil = DateTime.parse(json['validUntil'] as String);

  final String id;
  final String name;
  final int? entriesRemaining;
  final DateTime validUntil;
}

class PersonalTrainingPreview {
  PersonalTrainingPreview.fromJson(Map<String, dynamic> json)
    : issues = [
        for (final issue in json['issues'] as List)
          PlanIssue.fromJson(issue as Map<String, dynamic>),
      ],
      participantReason = json['participantReason'] as String?,
      passes = [
        for (final pass in json['eligiblePasses'] as List)
          PersonalPlanningPass.fromJson(pass as Map<String, dynamic>),
      ],
      startsCooperation = json['startsCooperation'] as bool;

  final List<PlanIssue> issues;
  final String? participantReason;
  final List<PersonalPlanningPass> passes;
  final bool startsCooperation;

  bool get allowed =>
      participantReason == null && !issues.any((i) => i.blocking);
}

class PersonalTrainingPlanningApi {
  const PersonalTrainingPlanningApi(this._dio);
  final Dio _dio;
  static const path = '/api/v1/me/personal-trainings';

  Future<List<PlanningServiceType>> services() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        serviceTypesPath,
        queryParameters: {'active': true},
      );
      return [
        for (final item in response.data!['items'] as List)
          if ((item as Map<String, dynamic>)['category'] == 'PERSONAL_TRAINING')
            PlanningServiceType.fromJson(item),
      ];
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }

  Future<PersonalTrainingPreview> preview(PersonalTrainingPlan plan) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '$path/preview',
        data: plan.toJson(),
      );
      return PersonalTrainingPreview.fromJson(response.data!);
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }

  Future<String> create(PersonalTrainingPlan plan, String passId) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        path,
        data: {'plan': plan.toJson(), 'customerPassId': passId},
      );
      return response.data!['eventId'] as String;
    } on DioException catch (e) {
      throw planRejection(e);
    }
  }
}

final personalTrainingPlanningApiProvider = Provider(
  (ref) => PersonalTrainingPlanningApi(ref.watch(dioProvider)),
);

final personalPlanningServicesProvider =
    FutureProvider.autoDispose<List<PlanningServiceType>>(
      (ref) => ref.watch(personalTrainingPlanningApiProvider).services(),
    );
