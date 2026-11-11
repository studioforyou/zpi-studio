import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/trainings/data/class_booking_api.dart';

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
  late ClassBookingApi api;

  setUp(() {
    adapter = _Adapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = ClassBookingApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test('reads what booking a client on the trainer\'s class takes', () async {
    adapter.body = {
      'eventId': 'event-1',
      'customerId': 'customer-1',
      'allowed': true,
      'reason': null,
      'reservationId': null,
      'waitlistStatus': 'OFFERED',
      'eligiblePasses': [
        {
          'id': 'pass-1',
          'productName': 'Karnet 8 wejść',
          'entriesRemaining': 5,
          'validUntil': '2026-12-31',
        },
        {
          'id': 'pass-2',
          'productName': 'Karnet open',
          'entriesRemaining': null,
          'validUntil': null,
        },
      ],
    };

    final options = await api.fetchOptions('event-1', 'customer-1');

    expect(
      adapter.request!.path,
      '/api/v1/me/schedule-events/event-1/booking-options',
    );
    expect(adapter.request!.queryParameters, {'customerId': 'customer-1'});
    expect(options.allowed, isTrue);
    expect(options.reason, isNull);
    expect(options.waitlistStatus, 'OFFERED');
    expect(options.eligiblePasses.first.entriesRemaining, 5);
    expect(options.eligiblePasses.first.validUntil, DateTime(2026, 12, 31));
    expect(options.eligiblePasses.last.entriesRemaining, isNull);
    expect(options.eligiblePasses.last.validUntil, isNull);
  });

  test('books the client with the pass and the trimmed reason', () async {
    adapter
      ..statusCode = 201
      ..body = {'reservationId': 'reservation-1'};

    await api.book(
      'event-1',
      customerId: 'customer-1',
      passId: 'pass-1',
      reason: '  Zgłosiła się po zajęciach ',
    );

    expect(adapter.request!.method, 'POST');
    expect(
      adapter.request!.path,
      '/api/v1/me/schedule-events/event-1/reservations',
    );
    expect(adapter.request!.data, {
      'customerId': 'customer-1',
      'customerPassId': 'pass-1',
      'reason': 'Zgłosiła się po zajęciach',
    });
  });

  test('a blank reason is not sent', () async {
    adapter.body = {'reservationId': 'reservation-1'};

    await api.book(
      'event-1',
      customerId: 'customer-1',
      passId: 'pass-1',
      reason: '   ',
    );

    expect((adapter.request!.data as Map)['reason'], isNull);
  });

  test('a rejection keeps the backend\'s code', () async {
    adapter
      ..statusCode = 409
      ..body = {'code': 'CLASS_FULL'};

    await expectLater(
      api.book('event-1', customerId: 'customer-1', passId: 'pass-1'),
      throwsA(
        isA<ClassBookingException>()
            .having((e) => e.statusCode, 'statusCode', 409)
            .having((e) => e.code, 'code', 'CLASS_FULL'),
      ),
    );
  });
}
