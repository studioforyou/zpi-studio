import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/token_refresher.dart';
import 'package:mobile/core/auth/token_storage.dart';
import 'package:mobile/core/network/auth_interceptor.dart';

final dioProvider = Provider<Dio>((ref) {
  final config = ref.read(appConfigProvider);
  final tokenStorage = ref.read(tokenStorageProvider);
  final options = BaseOptions(
    baseUrl: config.apiBaseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
  );
  final dio = Dio(options);
  final refresher = TokenRefresher(Dio(options), tokenStorage);
  dio.interceptors.add(
    AuthInterceptor(
      dio,
      tokenStorage,
      refreshTokens: refresher.refresh,
      onUnauthorized: () => ref.read(authControllerProvider.notifier).logout(),
    ),
  );
  return dio;
});
