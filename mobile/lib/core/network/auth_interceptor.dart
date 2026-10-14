import 'package:dio/dio.dart';
import 'package:mobile/core/auth/token_storage.dart';

// TODO(auth): token refresh once the backend contract is agreed
class AuthInterceptor extends Interceptor {
  AuthInterceptor(this._tokenStorage, {required this.onUnauthorized});

  final TokenStorage _tokenStorage;
  final void Function() onUnauthorized;

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    final token = await _tokenStorage.readAccessToken();
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.response?.statusCode == 401) {
      onUnauthorized();
    }
    handler.next(err);
  }
}
