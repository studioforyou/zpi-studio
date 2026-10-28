import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';

const _trainee = {
  'customerId': 'customer-1',
  'kind': 'CHILD',
  'firstName': 'Zosia',
  'lastName': 'Dąbrowska',
  'displayName': 'Zosia Dąbrowska',
  'birthDate': null,
  'isActive': true,
  'assignedBy': 'account-1',
  'assignedAt': '2026-10-28T17:00:00Z',
};

class _TraineesAdapter implements HttpClientAdapter {
  int statusCode = 200;
  Object? body;
  RequestOptions? request;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    request = options;
    return ResponseBody.fromString(
      body == null ? '' : jsonEncode(body),
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
  late _TraineesAdapter adapter;
  late Dio dio;
  late TraineesApi api;

  setUp(() {
    adapter = _TraineesAdapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = TraineesApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test('reads the own trainees in local time', () async {
    adapter.body = {
      'items': [_trainee],
    };

    final [trainee] = await api.fetchOwnTrainees();

    expect(adapter.request?.method, 'GET');
    expect(adapter.request?.path, '/api/v1/me/trainees');
    expect(trainee.customerId, 'customer-1');
    expect(trainee.displayName, 'Zosia Dąbrowska');
    expect(trainee.isChild, isTrue);
    expect(trainee.isActive, isTrue);
    expect(trainee.assignedAt, DateTime.utc(2026, 10, 28, 17).toLocal());
    expect(trainee.assignedAt.isUtc, isFalse);
  });

  test('searches customers by a trimmed query', () async {
    adapter.body = {
      'items': [
        {
          'id': 'customer-2',
          'kind': 'ADULT',
          'firstName': 'Katarzyna',
          'lastName': 'Zielińska',
          'displayName': 'Katarzyna Zielińska',
          'birthDate': '1990-05-01',
        },
      ],
      'page': 0,
      'size': 20,
      'totalElements': 1,
      'totalPages': 1,
    };

    final [customer] = await api.searchCustomers('  ziel ');

    expect(adapter.request?.path, '/api/v1/customers');
    expect(adapter.request?.queryParameters, {'query': 'ziel'});
    expect(customer.id, 'customer-2');
    expect(customer.isChild, isFalse);
  });

  test('sends no query for an empty search', () async {
    adapter.body = {'items': <Object>[]};

    await api.searchCustomers(' ');

    expect(adapter.request?.queryParameters, isEmpty);
  });

  test('assigns a customer to the signed-in trainer', () async {
    adapter
      ..statusCode = 201
      ..body = _trainee;

    final trainee = await api.assign('customer-1');

    expect(adapter.request?.method, 'POST');
    expect(adapter.request?.path, '/api/v1/me/trainees');
    expect(adapter.request?.data, {'customerId': 'customer-1'});
    expect(trainee.customerId, 'customer-1');
  });

  test('ends the cooperation with a customer', () async {
    adapter.statusCode = 204;

    await api.unassign('customer-1');

    expect(adapter.request?.method, 'DELETE');
    expect(adapter.request?.path, '/api/v1/me/trainees/customer-1');
  });

  test('maps HTTP errors to ApiException', () async {
    adapter
      ..statusCode = 409
      ..body = {'code': 'TRAINER_ALREADY_ASSIGNED'};

    await expectLater(
      api.assign('customer-1'),
      throwsA(
        isA<ApiException>().having((e) => e.statusCode, 'statusCode', 409),
      ),
    );
  });
}
