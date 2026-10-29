import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';

/// Answers every request with [body] and [statusCode] and remembers it.
class _PlanningAdapter implements HttpClientAdapter {
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

const _preview = {
  'occurrences': [
    {
      'date': '2026-11-03',
      'startsAt': '2026-11-03T17:00:00Z',
      'endsAt': '2026-11-03T18:00:00Z',
      'excluded': false,
      'issues': [
        {
          'type': 'RESOURCE_BUSY',
          'blocking': true,
          'staffId': null,
          'resourceId': 'room-1',
          'eventId': 'event-1',
          'eventTitle': 'Calisthenics',
          'startsAt': '2026-11-03T16:30:00Z',
          'endsAt': '2026-11-03T17:30:00Z',
        },
      ],
    },
    {
      'date': '2026-11-10',
      'startsAt': '2026-11-10T17:00:00Z',
      'endsAt': '2026-11-10T18:00:00Z',
      'excluded': true,
      'issues': <Object>[],
    },
  ],
  'warnings': [
    {
      'type': 'STAFF_NOT_QUALIFIED',
      'blocking': false,
      'staffId': 'staff-1',
      'resourceId': null,
      'eventId': null,
      'eventTitle': null,
      'startsAt': null,
      'endsAt': null,
    },
  ],
  'conflictCount': 1,
};

void main() {
  late _PlanningAdapter adapter;
  late Dio dio;
  late ClassPlanningApi api;

  setUp(() {
    adapter = _PlanningAdapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = ClassPlanningApi(dio);
  });

  tearDown(() => dio.close(force: true));

  final oneOff = OneOffClassPlan(
    serviceTypeId: 'service-1',
    roomId: 'room-1',
    startsAt: DateTime(2026, 11, 3, 18),
    endsAt: DateTime(2026, 11, 3, 19),
  );

  test('lists only active group class types, by name', () async {
    adapter.body = {
      'items': [
        {
          'id': 'pt',
          'name': 'Trening personalny',
          'category': 'PERSONAL_TRAINING',
          'defaultDurationMinutes': 60,
          'maxParticipants': 1,
        },
        {
          'id': 'pole',
          'name': 'Pole dance',
          'category': 'GROUP_CLASS',
          'defaultDurationMinutes': 60,
          'maxParticipants': 10,
        },
        {
          'id': 'cali',
          'name': 'Calisthenics',
          'category': 'GROUP_CLASS',
          'defaultDurationMinutes': 75,
          'maxParticipants': null,
        },
      ],
    };

    final types = await api.fetchServiceTypes();

    expect(adapter.requests.single.path, '/api/v1/service-types');
    expect(adapter.requests.single.queryParameters, {'active': true});
    expect([for (final type in types) type.id], ['cali', 'pole']);
    expect(types.first.defaultDurationMinutes, 75);
    expect(types.first.maxParticipants, isNull);
  });

  test('lists active rooms', () async {
    adapter.body = {
      'items': [
        {'id': 'room-1', 'name': 'Gym', 'kind': 'ROOM', 'maxPeople': 12},
      ],
    };

    final [room] = await api.fetchRooms();

    expect(adapter.requests.single.path, '/api/v1/resources');
    expect(adapter.requests.single.queryParameters, {'isActive': true});
    expect(room.name, 'Gym');
    expect(room.maxPeople, 12);
  });

  test('sends a one-off class in UTC without staff', () async {
    adapter.statusCode = 201;
    await api.create(oneOff);

    final request = adapter.requests.single;
    expect(request.method, 'POST');
    expect(request.path, '/api/v1/me/schedule-events');
    expect(request.data, {
      'serviceTypeId': 'service-1',
      'resources': [
        {'resourceId': 'room-1', 'blocksWholeRoom': true},
      ],
      'startsAt': DateTime(2026, 11, 3, 18).toUtc().toIso8601String(),
      'endsAt': DateTime(2026, 11, 3, 19).toUtc().toIso8601String(),
    });
  });

  test('sends a recurring class in studio local dates and time', () async {
    adapter.body = _preview;
    await api.preview(
      RecurringClassPlan(
        serviceTypeId: 'service-1',
        roomId: 'room-1',
        capacity: 8,
        weekdays: {4, 2},
        startTime: const TimeOfDay(hour: 7, minute: 5),
        durationMinutes: 45,
        startsOn: DateTime(2026, 11, 2),
        endsOn: DateTime(2026, 12, 31),
        excludedDates: {DateTime(2026, 11, 26), DateTime(2026, 11, 10)},
      ),
    );

    final request = adapter.requests.single;
    expect(request.path, '/api/v1/me/recurrence-series/preview');
    expect(request.data, {
      'serviceTypeId': 'service-1',
      'capacity': 8,
      'resources': [
        {'resourceId': 'room-1', 'blocksWholeRoom': true},
      ],
      'weekdays': [2, 4],
      'localStartTime': '07:05',
      'durationMinutes': 45,
      'startsOn': '2026-11-02',
      'endsOn': '2026-12-31',
      'excludedDates': ['2026-11-10', '2026-11-26'],
    });
  });

  test('reads the preview with its conflicts and warnings', () async {
    adapter.body = _preview;

    final preview = await api.preview(oneOff);

    expect(adapter.requests.single.path, '/api/v1/me/schedule-events/preview');
    expect(preview.conflictCount, 1);
    final [busy, excluded] = preview.occurrences;
    expect(busy.date, DateTime(2026, 11, 3));
    expect(busy.startsAt, DateTime.utc(2026, 11, 3, 17).toLocal());
    expect(busy.hasBlockingIssue, isTrue);
    expect(busy.issues.single.type, IssueType.roomBusy);
    expect(busy.issues.single.eventTitle, 'Calisthenics');
    expect(
      busy.issues.single.startsAt,
      DateTime.utc(2026, 11, 3, 16, 30).toLocal(),
    );
    expect(excluded.excluded, isTrue);
    expect(excluded.hasBlockingIssue, isFalse);
    expect(preview.warnings.single.type, IssueType.notQualified);
    expect(preview.warnings.single.blocking, isFalse);
  });

  test('a refused plan carries its error code and field', () async {
    adapter
      ..statusCode = 422
      ..body = {
        'title': 'Unprocessable Entity',
        'status': 422,
        'detail': 'The service may not use this room.',
        'code': 'SERVICE_NOT_ALLOWED_IN_ROOM',
      };
    await expectLater(
      api.preview(oneOff),
      throwsA(
        isA<PlanRejectedException>()
            .having((e) => e.statusCode, 'statusCode', 422)
            .having((e) => e.code, 'code', 'SERVICE_NOT_ALLOWED_IN_ROOM'),
      ),
    );

    adapter
      ..statusCode = 400
      ..body = {
        'code': 'VALIDATION_FAILED',
        'errors': [
          {'field': 'startsAt', 'code': 'OUT_OF_RANGE', 'message': 'past'},
        ],
      };
    await expectLater(
      api.create(oneOff),
      throwsA(
        isA<PlanRejectedException>().having(
          (e) => e.field,
          'field',
          'startsAt',
        ),
      ),
    );
  });

  test('a failure without a problem body is an ApiException', () async {
    adapter
      ..statusCode = 500
      ..body = 'oops';
    await expectLater(
      api.create(oneOff),
      throwsA(
        isA<ApiException>()
            .having((e) => e, 'type', isNot(isA<PlanRejectedException>()))
            .having((e) => e.statusCode, 'statusCode', 500),
      ),
    );
  });
}
