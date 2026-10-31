import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/accounts/data/customer_profile.dart';

String customerProfilePath(String profileId) =>
    '/api/v1/me/customer-profiles/$profileId';

class CustomerProfileApi {
  const CustomerProfileApi(this._dio);

  final Dio _dio;

  Future<CustomerProfile> fetchProfile(String profileId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        customerProfilePath(profileId),
      );
      return CustomerProfile.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Sends the form's complete values; blank optional values explicitly clear
  /// them.
  Future<CustomerProfile> updateProfile(
    String profileId, {
    required String firstName,
    required String lastName,
    required DateTime? birthDate,
    required String phone,
    required String emergencyContactName,
    required String emergencyPhone,
  }) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        customerProfilePath(profileId),
        data: {
          'firstName': firstName.trim(),
          'lastName': lastName.trim(),
          'birthDate': birthDate == null ? null : _isoDate(birthDate),
          'phone': _blankToNull(phone),
          'emergencyContactName': _blankToNull(emergencyContactName),
          'emergencyPhone': _blankToNull(emergencyPhone),
        },
      );
      return CustomerProfile.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  static String? _blankToNull(String value) {
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  static String _isoDate(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}

final customerProfileApiProvider = Provider<CustomerProfileApi>(
  (ref) => CustomerProfileApi(ref.watch(dioProvider)),
);
