import 'package:dio/dio.dart';

class ApiException implements Exception {
  const ApiException({this.statusCode, required this.message});

  factory ApiException.fromDio(DioException e) {
    final message = e.message;
    return ApiException(
      statusCode: e.response?.statusCode,
      message: message != null && message.isNotEmpty
          ? message
          : 'Network error',
    );
  }

  final int? statusCode;
  final String message;

  @override
  String toString() => 'ApiException($statusCode): $message';
}
