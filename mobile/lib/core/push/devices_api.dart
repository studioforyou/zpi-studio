import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// The signed-in account's devices that receive push notifications.
class DevicesApi {
  const DevicesApi(this._dio);

  final Dio _dio;

  /// Registers (or refreshes) [token] for the signed-in account. A token used
  /// by another account before moves to this one.
  Future<void> register(String token, {required String platform}) async {
    try {
      await _dio.post<void>(
        '/api/v1/me/devices',
        data: {'token': token, 'platform': platform},
      );
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }

  /// Stops pushes to [token], e.g. before signing out.
  Future<void> unregister(String token) async {
    try {
      await _dio.delete<void>(
        '/api/v1/me/devices/${Uri.encodeComponent(token)}',
      );
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final devicesApiProvider = Provider<DevicesApi>(
  (ref) => DevicesApi(ref.watch(dioProvider)),
);
