import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';

/// A current guardian of a child participant.
class TraineeGuardian {
  const TraineeGuardian({
    required this.displayName,
    required this.phone,
    required this.relation,
  });

  factory TraineeGuardian.fromJson(Map<String, dynamic> json) =>
      TraineeGuardian(
        displayName: json['displayName'] as String,
        phone: json['phone'] as String?,
        relation: json['relation'] as String,
      );

  final String displayName;
  final String? phone;

  /// Backend code, e.g. `PARENT`.
  final String relation;
}

/// Personal and contact details of a participant the trainer works with
/// (`OwnTraineeController#profile` in the backend). Never has an e-mail.
class TraineeProfile {
  const TraineeProfile({
    required this.customerId,
    required this.displayName,
    required this.isChild,
    required this.birthDate,
    required this.phone,
    required this.emergencyContactName,
    required this.emergencyPhone,
    required this.guardians,
    required this.assignedAt,
  });

  factory TraineeProfile.fromJson(Map<String, dynamic> json) => TraineeProfile(
    customerId: json['customerId'] as String,
    displayName: json['displayName'] as String,
    isChild: json['kind'] == 'CHILD',
    birthDate: json['birthDate'] == null
        ? null
        : DateTime.parse(json['birthDate'] as String),
    phone: json['phone'] as String?,
    emergencyContactName: json['emergencyContactName'] as String?,
    emergencyPhone: json['emergencyPhone'] as String?,
    guardians: [
      for (final item in json['guardians'] as List)
        TraineeGuardian.fromJson(item as Map<String, dynamic>),
    ],
    assignedAt: DateTime.parse(json['assignedAt'] as String).toLocal(),
  );

  final String customerId;
  final String displayName;
  final bool isChild;

  /// A calendar date without a time zone, or `null` if the studio does not
  /// know it.
  final DateTime? birthDate;
  final String? phone;
  final String? emergencyContactName;
  final String? emergencyPhone;
  final List<TraineeGuardian> guardians;

  /// Start of the cooperation, in the device's local time.
  final DateTime assignedAt;

  /// Full years on [today], or `null` when the birth date is unknown.
  int? ageOn(DateTime today) {
    final born = birthDate;
    if (born == null) return null;
    final years = today.year - born.year;
    final hadBirthday =
        today.month > born.month ||
        (today.month == born.month && today.day >= born.day);
    return hadBirthday ? years : years - 1;
  }
}

class TraineeProfileApi {
  const TraineeProfileApi(this._dio);
  final Dio _dio;

  Future<TraineeProfile> fetch(String customerId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/trainees/$customerId/profile',
      );
      return TraineeProfile.fromJson(response.data!);
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final traineeProfileApiProvider = Provider<TraineeProfileApi>(
  (ref) => TraineeProfileApi(ref.watch(dioProvider)),
);

final traineeProfileProvider = FutureProvider.autoDispose
    .family<TraineeProfile, TraineeOverviewQuery>((ref, query) {
      if (trainerOverviewScope(ref.watch(authControllerProvider)) !=
          query.scope) {
        throw const ApiException(
          statusCode: 403,
          message: 'Trainer scope changed.',
        );
      }
      return ref.watch(traineeProfileApiProvider).fetch(query.customerId);
    });
