import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/token_storage.dart';
import 'package:mobile/core/network/auth_interceptor.dart';

/// Keeps tokens in memory instead of the platform secure storage.
class _MemoryTokenStorage implements TokenStorage {
  _MemoryTokenStorage({this.accessToken});

  String? accessToken;
  String? refreshToken;

  @override
  Future<String?> readAccessToken() async => accessToken;

  @override
  Future<String?> readRefreshToken() async => refreshToken;

  @override
  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
  }

  @override
  Future<void> clear() async {
    accessToken = null;
    refreshToken = null;
  }
}

/// Answers 200 only to requests carrying the valid token, 401 otherwise.
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.validToken);

  final String validToken;
  final List<String?> sentAuthHeaders = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final header = options.headers['Authorization'] as String?;
    sentAuthHeaders.add(header);
    return header == 'Bearer $validToken'
        ? ResponseBody.fromString('ok', 200)
        : ResponseBody.fromString('', 401);
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _MemoryTokenStorage storage;
  late _FakeAdapter adapter;
  late Dio dio;
  late int refreshCalls;
  late int logoutCalls;

  void setUpDio({required Future<bool> Function() refresh}) {
    dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
    dio.interceptors.add(
      AuthInterceptor(
        dio,
        storage,
        refreshTokens: () {
          refreshCalls++;
          return refresh();
        },
        onUnauthorized: () => logoutCalls++,
      ),
    );
  }

  setUp(() {
    storage = _MemoryTokenStorage(accessToken: 'expired');
    adapter = _FakeAdapter('fresh');
    refreshCalls = 0;
    logoutCalls = 0;
  });

  test('refreshes once and retries the request', () async {
    setUpDio(
      refresh: () async {
        storage.accessToken = 'fresh';
        return true;
      },
    );

    final response = await dio.get<String>('/data');

    expect(response.statusCode, 200);
    expect(adapter.sentAuthHeaders, ['Bearer expired', 'Bearer fresh']);
    expect(refreshCalls, 1);
    expect(logoutCalls, 0);
  });

  test('concurrent 401s share one refresh', () async {
    setUpDio(
      refresh: () async {
        await Future<void>.delayed(const Duration(milliseconds: 10));
        storage.accessToken = 'fresh';
        return true;
      },
    );

    await Future.wait([dio.get<String>('/a'), dio.get<String>('/b')]);

    expect(refreshCalls, 1);
    expect(logoutCalls, 0);
  });

  test('logs out when the refresh fails', () async {
    setUpDio(refresh: () async => false);

    await expectLater(dio.get<String>('/data'), throwsA(isA<DioException>()));

    expect(refreshCalls, 1);
    expect(logoutCalls, 1);
  });

  test('logs out when the retried request is rejected again', () async {
    setUpDio(
      refresh: () async {
        storage.accessToken = 'still-wrong';
        return true;
      },
    );

    await expectLater(dio.get<String>('/data'), throwsA(isA<DioException>()));

    expect(refreshCalls, 1);
    expect(logoutCalls, 1);
  });

  test('does not refresh requests sent without a token', () async {
    storage.accessToken = null;
    setUpDio(refresh: () async => true);

    await expectLater(dio.get<String>('/login'), throwsA(isA<DioException>()));

    expect(refreshCalls, 0);
    expect(logoutCalls, 0);
  });
}
