import 'package:dio/dio.dart';

/// Adds the signed-in user's ID token to every request.
class AuthInterceptor extends Interceptor {
  AuthInterceptor(this._idToken, {required this.onUnauthorized});

  /// Returns the current ID token, or `null` when nobody is signed in.
  /// Firebase refreshes an expired token on its own.
  final Future<String?> Function() _idToken;
  final void Function() onUnauthorized;

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    final token = await _idToken();
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
