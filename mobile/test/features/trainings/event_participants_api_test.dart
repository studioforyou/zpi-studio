import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';

class _Adapter implements HttpClientAdapter {
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
  late _Adapter adapter;
  late Dio dio;
  late EventParticipantsApi api;

  setUp(() {
    adapter = _Adapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = EventParticipantsApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test('reads the participants of the trainer\'s event', () async {
    adapter.body = {
      'items': [
        {
          'reservationId': 'reservation-1',
          'customerId': 'customer-1',
          'kind': 'CHILD',
          'firstName': 'Zosia',
          'lastName': 'Dąbrowska',
          'displayName': 'Zosia Dąbrowska',
          'birthDate': '2015-05-12',
          'bookedAt': '2026-10-28T17:00:00Z',
        },
        {
          'reservationId': 'reservation-2',
          'customerId': 'customer-2',
          'kind': 'ADULT',
          'firstName': 'Ola',
          'lastName': 'Nowak',
          'displayName': 'Ola Nowak',
          'birthDate': null,
          'bookedAt': '2026-10-29T08:30:00Z',
        },
      ],
    };

    final [child, adult] = await api.fetch('event-1');

    expect(adapter.request?.method, 'GET');
    expect(
      adapter.request?.path,
      '/api/v1/me/schedule-events/event-1/participants',
    );
    expect(child.reservationId, 'reservation-1');
    expect(child.customerId, 'customer-1');
    expect(child.displayName, 'Zosia Dąbrowska');
    expect(child.isChild, isTrue);
    expect(child.birthDate, DateTime(2015, 5, 12));
    expect(child.bookedAt, DateTime.utc(2026, 10, 28, 17).toLocal());
    expect(adult.isChild, isFalse);
    expect(adult.birthDate, isNull);
  });

  test('a missing event is reported with its status', () async {
    adapter
      ..statusCode = 404
      ..body = {'code': 'NOT_FOUND'};

    await expectLater(
      api.fetch('event-1'),
      throwsA(
        isA<ApiException>().having((e) => e.statusCode, 'statusCode', 404),
      ),
    );
  });

  test('age is counted in whole years on the given day', () {
    final participant = EventParticipant(
      reservationId: 'reservation-1',
      customerId: 'customer-1',
      displayName: 'Zosia Dąbrowska',
      isChild: true,
      birthDate: DateTime(2016, 2, 29),
      bookedAt: DateTime(2026),
    );

    expect(participant.ageOn(DateTime(2026, 2, 28)), 9);
    expect(participant.ageOn(DateTime(2026, 3, 1)), 10);
    expect(participant.ageOn(DateTime(2028, 2, 29)), 12);
  });
}
