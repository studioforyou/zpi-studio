import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/accounts/data/staff_profile_api.dart';

class _ProfileAdapter implements HttpClientAdapter {
  int statusCode = 200;
  RequestOptions? request;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    request = options;
    return ResponseBody.fromString(
      jsonEncode({
        'id': 'staff-1',
        'firstName': 'Anna',
        'lastName': 'Nowak',
        'phone': null,
      }),
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
  late _ProfileAdapter adapter;
  late Dio dio;
  late StaffProfileApi api;

  setUp(() {
    adapter = _ProfileAdapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = StaffProfileApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test(
    'fetches the signed-in staff profile without a caller-supplied id',
    () async {
      final profile = await api.fetchProfile();

      expect(adapter.request?.method, 'GET');
      expect(adapter.request?.path, '/api/v1/me/staff-profile');
      expect(adapter.request?.queryParameters, isEmpty);
      expect(profile.id, 'staff-1');
      expect(profile.displayName, 'Anna Nowak');
      expect(profile.phone, isNull);
    },
  );

  test(
    'patches trimmed form values and sends null to clear the phone',
    () async {
      await api.updateProfile(
        firstName: '\u00a0Anna\ufeff',
        lastName: ' Nowak ',
        phone: ' \t ',
      );

      expect(adapter.request?.method, 'PATCH');
      expect(adapter.request?.path, '/api/v1/me/staff-profile');
      expect(adapter.request?.data, {
        'firstName': 'Anna',
        'lastName': 'Nowak',
        'phone': null,
      });
    },
  );

  test('preserves formatting within a trimmed phone number', () async {
    await api.updateProfile(
      firstName: 'Anna',
      lastName: 'Nowak',
      phone: ' +48 123 456 789 ',
    );

    expect((adapter.request?.data as Map)['phone'], '+48 123 456 789');
  });

  test('a rejected update fails instead of returning a profile', () async {
    adapter.statusCode = 403;

    await expectLater(
      api.updateProfile(firstName: 'Anna', lastName: 'Nowak', phone: ''),
      throwsA(
        isA<ApiException>().having((e) => e.statusCode, 'statusCode', 403),
      ),
    );
  });
}
