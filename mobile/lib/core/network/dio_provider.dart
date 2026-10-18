import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/firebase_auth_repository.dart';
import 'package:mobile/core/network/auth_interceptor.dart';

final dioProvider = Provider<Dio>((ref) {
  final config = ref.read(appConfigProvider);
  final dio = Dio(
    BaseOptions(
      baseUrl: config.apiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
    ),
  );
  dio.interceptors.add(
    AuthInterceptor(
      () async => ref.read(firebaseAuthProvider).currentUser?.getIdToken(),
      onUnauthorized: () => ref.read(authControllerProvider.notifier).logout(),
    ),
  );
  return dio;
});
