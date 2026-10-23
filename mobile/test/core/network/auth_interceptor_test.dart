import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/auth_interceptor.dart';

/// Answers every request with [statusCode] and remembers its headers.
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.statusCode);

  final int statusCode;
  Map<String, dynamic>? lastHeaders;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    lastHeaders = options.headers;
    return ResponseBody.fromString(
      jsonEncode({}),
      statusCode,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _FakeAdapter adapter;
  late int unauthorizedCalls;

  Dio dio({required int statusCode, String? token}) {
    adapter = _FakeAdapter(statusCode);
    unauthorizedCalls = 0;
    return Dio()
      ..httpClientAdapter = adapter
      ..interceptors.add(
        AuthInterceptor(
          () async => token,
          onUnauthorized: () => unauthorizedCalls++,
        ),
      );
  }

  test('the ID token is sent as a bearer token', () async {
    await dio(statusCode: 200, token: 'id-token').get<Object?>('/x');

    expect(adapter.lastHeaders?['Authorization'], 'Bearer id-token');
  });

  test('without a signed-in user no token is sent', () async {
    await dio(statusCode: 200).get<Object?>('/x');

    expect(adapter.lastHeaders, isNot(contains('Authorization')));
  });

  test('a 401 logs the user out', () async {
    final client = dio(statusCode: 401, token: 'expired');

    await expectLater(client.get<Object?>('/x'), throwsA(isA<DioException>()));
    expect(unauthorizedCalls, 1);
  });

  test('other errors do not log the user out', () async {
    final client = dio(statusCode: 403, token: 'id-token');

    await expectLater(client.get<Object?>('/x'), throwsA(isA<DioException>()));
    expect(unauthorizedCalls, 0);
  });
}
