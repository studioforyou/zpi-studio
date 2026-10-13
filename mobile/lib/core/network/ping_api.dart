import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// Connection test endpoint (`PingController` in the backend).
const pingPath = '/api/ping';

class PingApi {
  const PingApi(this._dio);

  final Dio _dio;

  /// Returns the backend's response body.
  Future<String> ping() async {
    try {
      final response = await _dio.get<String>(
        pingPath,
        options: Options(responseType: ResponseType.plain),
      );
      return response.data ?? '';
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final pingApiProvider = Provider<PingApi>(
  (ref) => PingApi(ref.watch(dioProvider)),
);
