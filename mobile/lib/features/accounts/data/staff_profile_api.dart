import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/accounts/data/staff_profile.dart';

const staffProfilePath = '/api/v1/me/staff-profile';

class StaffProfileApi {
  const StaffProfileApi(this._dio);

  final Dio _dio;

  Future<StaffProfile> fetchProfile() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(staffProfilePath);
      return StaffProfile.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Sends the form's complete values; a blank phone explicitly clears it.
  Future<StaffProfile> updateProfile({
    required String firstName,
    required String lastName,
    required String phone,
  }) {
    return updateSharedDetails(
      firstName: firstName,
      lastName: lastName,
      phone: phone,
    );
  }

  /// Changes only the given details; `null` leaves a detail as it is and a
  /// blank phone clears it.
  Future<StaffProfile> updateSharedDetails({
    String? firstName,
    String? lastName,
    String? phone,
  }) async {
    final trimmedPhone = phone?.trim();
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        staffProfilePath,
        data: {
          if (firstName != null) 'firstName': firstName.trim(),
          if (lastName != null) 'lastName': lastName.trim(),
          if (trimmedPhone != null)
            'phone': trimmedPhone.isEmpty ? null : trimmedPhone,
        },
      );
      return StaffProfile.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final staffProfileApiProvider = Provider<StaffProfileApi>(
  (ref) => StaffProfileApi(ref.watch(dioProvider)),
);
