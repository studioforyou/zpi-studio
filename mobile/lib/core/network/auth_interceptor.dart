import 'package:dio/dio.dart';
import 'package:mobile/core/auth/token_storage.dart';

/// Sends the access token with every request. On a `401` it refreshes the
/// tokens once and retries the request; when the refresh fails or the retry
/// is rejected again, [onUnauthorized] logs the user out
/// (API_CONVENTIONS.md, section 5).
///
/// Requests sent without a token (login, other public operations) are never
/// refreshed: their `401` is a regular error such as wrong credentials.
class AuthInterceptor extends Interceptor {
  AuthInterceptor(
    this._dio,
    this._tokenStorage, {
    required this.refreshTokens,
    required this.onUnauthorized,
  });

  static const _retriedKey = 'authRetried';

  /// The client the failed request is retried with.
  final Dio _dio;
  final TokenStorage _tokenStorage;

  /// Gets new tokens; returns `false` when that is not possible.
  final Future<bool> Function() refreshTokens;
  final void Function() onUnauthorized;

  /// Shared by requests failing at the same time, so they refresh only once.
  Future<bool>? _refreshInProgress;

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
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    final request = err.requestOptions;
    final usedToken = _bearerToken(request);
    if (err.response?.statusCode != 401 || usedToken == null) {
      return handler.next(err);
    }
    if (request.extra[_retriedKey] == true) {
      onUnauthorized();
      return handler.next(err);
    }

    // Another request may already have replaced the expired token.
    final currentToken = await _tokenStorage.readAccessToken();
    final refreshed =
        (currentToken != null && currentToken != usedToken) ||
        await _refreshOnce();
    if (!refreshed) {
      onUnauthorized();
      return handler.next(err);
    }

    request.extra[_retriedKey] = true;
    try {
      handler.resolve(await _dio.fetch<Object?>(request));
    } on DioException catch (e) {
      handler.next(e);
    }
  }

  Future<bool> _refreshOnce() {
    return _refreshInProgress ??= refreshTokens().whenComplete(
      () => _refreshInProgress = null,
    );
  }

  static String? _bearerToken(RequestOptions request) {
    final header = request.headers['Authorization'];
    if (header is String && header.startsWith('Bearer ')) {
      return header.substring('Bearer '.length);
    }
    return null;
  }
}
