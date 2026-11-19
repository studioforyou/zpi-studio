import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/reschedule_api.dart';

/// Answers every request with [body] and [statusCode] and remembers it.
class _Adapter implements HttpClientAdapter {
  int statusCode = 200;
  Object body = const {};
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      jsonEncode(body),
      statusCode,
      headers: {
        Headers.contentTypeHeader: [
          statusCode >= 400
              ? 'application/problem+json'
              : Headers.jsonContentType,
        ],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

const _event = {
  'id': 'event-1',
  'kind': 'CLASS',
  'status': 'SCHEDULED',
  'title': 'Pole dance',
  'serviceTypeName': 'Pole dance',
  'startsAt': '2026-11-25T18:00:00Z',
  'endsAt': '2026-11-25T19:00:00Z',
  'capacity': 10,
  'staff': [
    {'staffId': 'anna', 'displayName': 'Anna Kowalska', 'role': 'LEAD'},
  ],
  'resources': [
    {'resourceId': 'gym', 'name': 'Gym', 'blocksWholeRoom': true},
  ],
  'isSeriesException': true,
  'seriesChange': null,
  'version': 3,
};

void main() {
  late _Adapter adapter;
  late Dio dio;
  late RescheduleApi api;

  setUp(() {
    adapter = _Adapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = RescheduleApi(dio);
  });

  tearDown(() => dio.close(force: true));

  final plan = ReschedulePlan(
    version: 2,
    startsAt: DateTime.utc(2026, 11, 25, 18),
    endsAt: DateTime.utc(2026, 11, 25, 19),
    roomId: 'gym',
  );

  test('previews the new time of the event', () async {
    adapter.body = {
      'occurrences': [
        {
          'date': '2026-11-25',
          'startsAt': '2026-11-25T18:00:00Z',
          'endsAt': '2026-11-25T19:00:00Z',
          'excluded': false,
          'issues': [
            {
              'type': 'STAFF_ON_LEAVE',
              'blocking': true,
              'staffId': 'anna',
              'resourceId': null,
              'eventId': null,
              'eventTitle': null,
              'startsAt': '2026-11-25T00:00:00Z',
              'endsAt': '2026-11-26T00:00:00Z',
            },
          ],
        },
      ],
      'warnings': <Object>[],
      'conflictCount': 1,
    };

    final preview = await api.preview('event-1', plan);

    final request = adapter.requests.single;
    expect(request.method, 'POST');
    expect(
      request.path,
      '/api/v1/me/schedule-events/event-1/reschedule-preview',
    );
    expect(request.data, {
      'version': 2,
      'startsAt': '2026-11-25T18:00:00.000Z',
      'endsAt': '2026-11-25T19:00:00.000Z',
      'resourceId': 'gym',
    });
    expect(preview.conflictCount, 1);
    expect(preview.occurrences.single.hasBlockingIssue, isTrue);
  });

  test('saves the new time and returns the moved event', () async {
    adapter.body = _event;

    final event = await api.reschedule('event-1', plan);

    expect(adapter.requests.single.method, 'PUT');
    expect(
      adapter.requests.single.path,
      '/api/v1/me/schedule-events/event-1/reschedule',
    );
    expect(event.eventVersion, 3);
    expect(event.roomIds, ['gym']);
    expect(event.roomNames, ['Gym']);
  });

  test('keeps the rooms when no room is chosen', () {
    final keep = ReschedulePlan(
      version: 0,
      startsAt: DateTime.utc(2026, 11, 25, 18),
      endsAt: DateTime.utc(2026, 11, 25, 19),
    );
    expect(keep.toJson()['resourceId'], isNull);
  });

  test('keeps the error code of a rejected change', () async {
    adapter
      ..statusCode = 409
      ..body = {
        'code': 'VERSION_CONFLICT',
        'detail': 'The event was modified by another request.',
      };

    await expectLater(
      api.reschedule('event-1', plan),
      throwsA(
        isA<PlanRejectedException>()
            .having((e) => e.code, 'code', 'VERSION_CONFLICT')
            .having((e) => e.statusCode, 'statusCode', 409),
      ),
    );
  });
}
