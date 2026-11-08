import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// The service catalogue (`ServiceTypeController` in the backend).
const scheduleServiceTypesPath = '/api/v1/service-types';

/// Who a service is meant for, by its age limits.
enum AgeGroup { children, adults }

/// The facts of a service type that schedule filters rely on. Events carry
/// only the service's id, so the calendar looks these up by that id, the same
/// way the web schedule does.
class ScheduleServiceType {
  const ScheduleServiceType({
    required this.id,
    required this.level,
    required this.minAge,
    required this.maxAge,
    this.name,
    this.category,
    this.isActive = true,
  });

  factory ScheduleServiceType.fromJson(Map<String, dynamic> json) {
    return ScheduleServiceType(
      id: json['id'] as String,
      level: json['level'] as String?,
      minAge: json['minAge'] as int?,
      maxAge: json['maxAge'] as int?,
      name: json['name'] as String?,
      category: json['category'] as String?,
      isActive: json['isActive'] as bool? ?? true,
    );
  }

  final String id;
  final String? name;

  /// Category code, e.g. `GROUP_CLASS` or `PERSONAL_TRAINING`.
  final String? category;
  final bool isActive;

  /// Whether the studio offers this service as public group classes now.
  bool get isOfferedGroupClass => isActive && category == 'GROUP_CLASS';

  /// Level code, e.g. `BEGINNER`, or `null` when the service has none.
  final String? level;

  /// Youngest allowed age, or `null` without a lower limit.
  final int? minAge;

  /// Oldest allowed age, or `null` without an upper limit.
  final int? maxAge;

  /// A service for children has an upper age limit below 18; every other
  /// service counts as one for adults. Matches the web schedule's rule.
  AgeGroup get ageGroup =>
      maxAge != null && maxAge! < 18 ? AgeGroup.children : AgeGroup.adults;
}

class ScheduleServiceTypesApi {
  const ScheduleServiceTypesApi(this._dio);

  final Dio _dio;

  /// Every service type, active or not, by id. Inactive ones are kept,
  /// because past and planned events may still refer to them.
  Future<Map<String, ScheduleServiceType>> fetchAll() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        scheduleServiceTypesPath,
      );
      final types = [
        for (final item in response.data!['items'] as List)
          ScheduleServiceType.fromJson(item as Map<String, dynamic>),
      ];
      return {for (final type in types) type.id: type};
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final scheduleServiceTypesApiProvider = Provider<ScheduleServiceTypesApi>(
  (ref) => ScheduleServiceTypesApi(ref.watch(dioProvider)),
);

/// Service types by id for schedule filters. When they cannot be loaded, the
/// calendar still works; only the level and age group filters are missing.
final scheduleServiceTypesProvider =
    FutureProvider.autoDispose<Map<String, ScheduleServiceType>>(
      (ref) => ref.watch(scheduleServiceTypesApiProvider).fetchAll(),
    );
