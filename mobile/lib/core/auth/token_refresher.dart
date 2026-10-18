import 'package:dio/dio.dart';
import 'package:mobile/core/auth/token_storage.dart';

const refreshPath = '/api/v1/auth/refresh';

/// Exchanges the stored refresh token for new tokens.
///
/// Uses its own [Dio] without the auth interceptor, so a failed refresh never
/// triggers another refresh.
class TokenRefresher {
  const TokenRefresher(this._dio, this._tokenStorage);

  final Dio _dio;
  final TokenStorage _tokenStorage;

  /// Returns `true` when new tokens were stored.
  // TODO(auth): confirm the refresh request and response bodies with the backend; the response is assumed to carry tokens like login does.
  Future<bool> refresh() async {
    final refreshToken = await _tokenStorage.readRefreshToken();
    if (refreshToken == null) return false;
    try {
      final response = await _dio.post<Object?>(
        refreshPath,
        data: {'refreshToken': refreshToken},
      );
      if (response.data case {
        'accessToken': String accessToken,
        'refreshToken': String newRefreshToken,
      }) {
        await _tokenStorage.saveTokens(
          accessToken: accessToken,
          refreshToken: newRefreshToken,
        );
        return true;
      }
      return false;
    } on DioException {
      return false;
    }
  }
}
