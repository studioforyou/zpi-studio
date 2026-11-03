import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/accounts/data/customer_profile_api.dart';

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
        'id': 'child-1',
        'kind': 'CHILD',
        'firstName': 'Jan',
        'lastName': 'Kowalski',
        'birthDate': '2016-02-01',
        'phone': null,
        'emergencyContactName': 'Anna',
        'emergencyPhone': '600100200',
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
  late CustomerProfileApi api;

  setUp(() {
    adapter = _ProfileAdapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = CustomerProfileApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test('fetches the profile by id and parses all fields', () async {
    final profile = await api.fetchProfile('child-1');

    expect(adapter.request?.method, 'GET');
    expect(adapter.request?.path, '/api/v1/me/customer-profiles/child-1');
    expect(profile.isChild, isTrue);
    expect(profile.displayName, 'Jan Kowalski');
    expect(profile.birthDate, DateTime(2016, 2, 1));
    expect(profile.phone, isNull);
    expect(profile.emergencyContactName, 'Anna');
  });

  test('sends trimmed values, an ISO date and nulls for blanks', () async {
    await api.updateProfile(
      'child-1',
      firstName: '  Jan ',
      lastName: 'Kowalski ',
      birthDate: DateTime(2016, 2, 1),
      phone: '   ',
      emergencyContactName: ' Anna ',
      emergencyPhone: ' 600100200 ',
    );

    expect(adapter.request?.method, 'PATCH');
    expect(adapter.request?.path, '/api/v1/me/customer-profiles/child-1');
    expect(adapter.request?.data, {
      'firstName': 'Jan',
      'lastName': 'Kowalski',
      'birthDate': '2016-02-01',
      'phone': null,
      'emergencyContactName': 'Anna',
      'emergencyPhone': '600100200',
    });
  });

  test('sends a null birth date to clear it', () async {
    await api.updateProfile(
      'child-1',
      firstName: 'Jan',
      lastName: 'Kowalski',
      birthDate: null,
      phone: '',
      emergencyContactName: '',
      emergencyPhone: '',
    );

    expect((adapter.request?.data as Map)['birthDate'], isNull);
  });

  test('maps HTTP errors to ApiException', () async {
    adapter.statusCode = 404;

    await expectLater(
      api.fetchProfile('other'),
      throwsA(
        isA<ApiException>().having((e) => e.statusCode, 'statusCode', 404),
      ),
    );
  });
}
