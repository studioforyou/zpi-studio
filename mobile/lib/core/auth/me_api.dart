import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/network/dio_provider.dart';

const mePath = '/api/v1/me';

/// `ACCOUNT_NOT_FOUND` in the backend's 403 response.
const _accountNotFoundCode = 'ACCOUNT_NOT_FOUND';

/// The signed-in account as returned by `GET /api/v1/me`.
class MeResponse {
  const MeResponse({
    required this.accountId,
    required this.status,
    required this.displayName,
    required this.roles,
    required this.staffProfileId,
    required this.profiles,
    required this.activeProfileId,
  });

  factory MeResponse.fromJson(Map<String, dynamic> json) {
    return MeResponse(
      accountId: json['accountId'] as String,
      status: json['status'] as String,
      displayName: json['displayName'] as String,
      roles: [for (final role in json['roles'] as List) role as String],
      staffProfileId: json['staffProfileId'] as String?,
      profiles: [
        for (final profile in json['profiles'] as List)
          (
            id: (profile as Map<String, dynamic>)['id'] as String,
            displayName: profile['displayName'] as String,
            isChild: profile['isChild'] as bool,
          ),
      ],
      activeProfileId: json['activeProfileId'] as String?,
    );
  }

  final String accountId;

  /// Account status, e.g. `ACTIVE` or `PENDING_APPROVAL`.
  final String status;
  final String displayName;

  /// Backend role codes, e.g. `CLIENT`, `TRAINER`.
  final List<String> roles;
  final String? staffProfileId;
  final List<({String id, String displayName, bool isChild})> profiles;
  final String? activeProfileId;
}

class MeApi {
  const MeApi(this._dio);

  final Dio _dio;

  /// Fetches the account behind the current Firebase ID token.
  ///
  /// Throws [AuthFailure] when the backend does not know the user or cannot be
  /// reached.
  Future<MeResponse> fetchMe() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(mePath);
      return MeResponse.fromJson(response.data!);
    } on DioException catch (e) {
      throw AuthFailure(_reasonFor(e));
    }
  }

  static AuthFailureReason _reasonFor(DioException e) {
    final response = e.response;
    if (response == null) {
      return AuthFailureReason.network;
    }
    final data = response.data;
    if (response.statusCode == 403 &&
        data is Map &&
        data['code'] == _accountNotFoundCode) {
      return AuthFailureReason.accountNotFound;
    }
    return AuthFailureReason.unknown;
  }
}

final meApiProvider = Provider<MeApi>((ref) => MeApi(ref.watch(dioProvider)));
