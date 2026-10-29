import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// Participants of the signed-in trainer (`OwnTraineeController` in the
/// backend).
const ownTraineesPath = '/api/v1/me/trainees';

/// Search of active participants (`CustomerController` in the backend).
const customersPath = '/api/v1/customers';

/// A participant the signed-in trainer works with.
class Trainee {
  const Trainee({
    required this.customerId,
    required this.displayName,
    required this.isChild,
    required this.birthDate,
    required this.isActive,
    required this.assignedAt,
  });

  factory Trainee.fromJson(Map<String, dynamic> json) {
    return Trainee(
      customerId: json['customerId'] as String,
      displayName: json['displayName'] as String,
      isChild: json['kind'] == 'CHILD',
      birthDate: _date(json['birthDate'] as String?),
      isActive: json['isActive'] as bool,
      assignedAt: DateTime.parse(json['assignedAt'] as String).toLocal(),
    );
  }

  final String customerId;
  final String displayName;
  final bool isChild;

  /// The date of birth, or `null` if the studio does not know it.
  final DateTime? birthDate;

  /// Whether the participant's profile is still active in the studio.
  final bool isActive;

  /// Start of the cooperation, in the device's local time.
  final DateTime assignedAt;
}

/// An active participant found by the search.
class CustomerSummary {
  const CustomerSummary({
    required this.id,
    required this.displayName,
    required this.isChild,
    required this.birthDate,
  });

  factory CustomerSummary.fromJson(Map<String, dynamic> json) {
    return CustomerSummary(
      id: json['id'] as String,
      displayName: json['displayName'] as String,
      isChild: json['kind'] == 'CHILD',
      birthDate: _date(json['birthDate'] as String?),
    );
  }

  final String id;
  final String displayName;
  final bool isChild;

  /// The date of birth, or `null` if the studio does not know it.
  final DateTime? birthDate;
}

/// A calendar date (`yyyy-MM-dd`) without a time zone, or `null`.
DateTime? _date(String? value) => value == null ? null : DateTime.parse(value);

class TraineesApi {
  const TraineesApi(this._dio);

  final Dio _dio;

  /// Participants the signed-in trainer works with, sorted by last and first
  /// name.
  Future<List<Trainee>> fetchOwnTrainees() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(ownTraineesPath);
      return [
        for (final item in response.data!['items'] as List)
          Trainee.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// The first page of active participants matching [query] (part of the
  /// name or phone number), sorted by last and first name.
  Future<List<CustomerSummary>> searchCustomers(String query) async {
    final trimmed = query.trim();
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        customersPath,
        queryParameters: {if (trimmed.isNotEmpty) 'query': trimmed},
      );
      return [
        for (final item in response.data!['items'] as List)
          CustomerSummary.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Starts the signed-in trainer's cooperation with the participant.
  Future<Trainee> assign(String customerId) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ownTraineesPath,
        data: {'customerId': customerId},
      );
      return Trainee.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Ends the cooperation; the studio keeps it as history.
  Future<void> unassign(String customerId) async {
    try {
      await _dio.delete<void>('$ownTraineesPath/$customerId');
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final traineesApiProvider = Provider<TraineesApi>(
  (ref) => TraineesApi(ref.watch(dioProvider)),
);

/// The signed-in trainer's trainees. The account id keeps one account's
/// trainees from being shown to another account.
final ownTraineesProvider = FutureProvider.autoDispose
    .family<List<Trainee>, String>(
      (ref, accountId) => ref.watch(traineesApiProvider).fetchOwnTrainees(),
    );

/// Active participants matching a search query.
final customerSearchProvider = FutureProvider.autoDispose
    .family<List<CustomerSummary>, String>(
      (ref, query) => ref.watch(traineesApiProvider).searchCustomers(query),
    );
