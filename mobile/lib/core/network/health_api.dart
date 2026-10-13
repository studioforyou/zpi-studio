import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

// TODO(#26): confirm the path with the backend skeleton
const healthPath = '/actuator/health';

class HealthApi {
  const HealthApi(this._dio);

  final Dio _dio;

  Future<bool> check() async {
    try {
      final response = await _dio.get<Object?>(healthPath);
      return response.statusCode == 200;
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final healthApiProvider = Provider<HealthApi>(
  (ref) => HealthApi(ref.watch(dioProvider)),
);

final backendHealthProvider = FutureProvider.autoDispose<bool>(
  (ref) => ref.watch(healthApiProvider).check(),
);
