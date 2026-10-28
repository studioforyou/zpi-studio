import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

class _ScheduleAdapter implements HttpClientAdapter {
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
        'items': [
          {
            'id': 'event-1',
            'kind': 'PERSONAL_TRAINING',
            'status': 'CANCELLED',
            'visibility': 'PRIVATE',
            'title': null,
            'serviceTypeId': 'service-1',
            'serviceTypeName': 'Personal training',
            'seriesId': null,
            'isSeriesException': false,
            'trainingGroupId': null,
            'startsAt': '2026-11-02T17:00:00Z',
            'endsAt': '2026-11-02T18:00:00Z',
            'capacity': 1,
            'staff': [
              {
                'staffId': 'staff-1',
                'displayName': 'Anna Kowalska',
                'role': 'LEAD',
              },
              {
                'staffId': 'staff-2',
                'displayName': 'Marek Nowak',
                'role': 'ASSISTANT',
              },
            ],
            'resources': [
              {
                'resourceId': 'room-1',
                'name': 'Gym',
                'kind': 'ROOM',
                'blocksWholeRoom': false,
              },
            ],
            'cancelReason': 'TRAINER_ABSENT',
            'blockReason': null,
            'guestName': null,
            'version': 1,
          },
        ],
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
  late _ScheduleAdapter adapter;
  late Dio dio;
  late ScheduleApi api;

  setUp(() {
    adapter = _ScheduleAdapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = ScheduleApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test('asks for the own schedule in a window sent in UTC', () async {
    final from = DateTime(2026, 11, 2);
    final to = DateTime(2026, 11, 9);
    await api.fetchOwnEvents((from: from, to: to));

    expect(adapter.request?.method, 'GET');
    expect(adapter.request?.path, '/api/v1/me/schedule-events');
    expect(adapter.request?.queryParameters, {
      'from': from.toUtc().toIso8601String(),
      'to': to.toUtc().toIso8601String(),
    });
  });

  test('reads events in local time with their staff and rooms', () async {
    final [event] = await api.fetchOwnEvents((
      from: DateTime(2026, 11, 2),
      to: DateTime(2026, 11, 3),
    ));

    expect(event.id, 'event-1');
    expect(event.kind, EventKind.personalTraining);
    expect(event.status, EventStatus.cancelled);
    expect(event.name, 'Personal training');
    expect(event.startsAt, DateTime.utc(2026, 11, 2, 17).toLocal());
    expect(event.startsAt.isUtc, isFalse);
    expect(event.endsAt, DateTime.utc(2026, 11, 2, 18).toLocal());
    expect(event.capacity, 1);
    expect([for (final s in event.staff) s.isLead], [true, false]);
    expect(event.roomNames, ['Gym']);
  });

  test('maps HTTP errors to ApiException', () async {
    adapter.statusCode = 404;
    await expectLater(
      api.fetchOwnEvents((
        from: DateTime(2026, 11, 2),
        to: DateTime(2026, 11, 3),
      )),
      throwsA(
        isA<ApiException>().having((e) => e.statusCode, 'statusCode', 404),
      ),
    );
  });
}
