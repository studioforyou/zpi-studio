import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
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

  test('reads the attendance sheet of the trainer\'s event', () async {
    adapter.body = {
      'eventId': 'event-1',
      'eventKind': 'CLASS',
      'eventStatus': 'COMPLETED',
      'markingOpensAt': '2026-11-03T17:00:00Z',
      'markingClosesAt': '2026-11-04T06:00:00Z',
      'markingOpen': true,
      'participants': [
        {
          'reservationId': 'reservation-1',
          'customerId': 'customer-1',
          'kind': 'CHILD',
          'firstName': 'Zosia',
          'lastName': 'Dąbrowska',
          'displayName': 'Zosia Dąbrowska',
          'birthDate': '2015-05-12',
          'bookedAt': '2026-10-28T17:00:00Z',
          'attendance': 'ABSENT',
          'attendanceMarkedAt': '2026-11-03T17:05:00Z',
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
          'attendance': 'UNMARKED',
          'attendanceMarkedAt': null,
        },
      ],
    };

    final sheet = await api.fetchAttendance('event-1');
    final [child, adult] = sheet.participants;

    expect(adapter.request?.method, 'GET');
    expect(
      adapter.request?.path,
      '/api/v1/me/schedule-events/event-1/attendance',
    );
    expect(sheet.eventStatus, EventStatus.completed);
    expect(sheet.markingOpen, isTrue);
    expect(sheet.markingOpensAt, DateTime.utc(2026, 11, 3, 17).toLocal());
    expect(sheet.markingClosesAt, DateTime.utc(2026, 11, 4, 6).toLocal());
    expect(sheet.count(Attendance.absent), 1);
    expect(sheet.count(Attendance.unmarked), 1);
    expect(child.reservationId, 'reservation-1');
    expect(child.customerId, 'customer-1');
    expect(child.displayName, 'Zosia Dąbrowska');
    expect(child.isChild, isTrue);
    expect(child.birthDate, DateTime(2015, 5, 12));
    expect(child.bookedAt, DateTime.utc(2026, 10, 28, 17).toLocal());
    expect(child.attendance, Attendance.absent);
    expect(adult.isChild, isFalse);
    expect(adult.birthDate, isNull);
    expect(adult.attendance, Attendance.unmarked);
  });

  test('records attendance and marks the event as held', () async {
    adapter.body = _sheet;

    await api.mark('event-1', {'reservation-1': Attendance.present});

    expect(adapter.request?.method, 'PATCH');
    expect(
      adapter.request?.path,
      '/api/v1/me/schedule-events/event-1/attendance',
    );
    expect(adapter.request?.data, {
      'marks': [
        {'reservationId': 'reservation-1', 'attendance': 'PRESENT'},
      ],
    });

    await api.complete('event-1');

    expect(adapter.request?.method, 'POST');
    expect(
      adapter.request?.path,
      '/api/v1/me/schedule-events/event-1/complete',
    );
  });

  test('a rejection is reported with its code', () async {
    adapter
      ..statusCode = 422
      ..body = {'code': 'ATTENDANCE_CLOSED'};

    await expectLater(
      api.mark('event-1', {'reservation-1': Attendance.absent}),
      throwsA(
        isA<AttendanceException>()
            .having((e) => e.statusCode, 'statusCode', 422)
            .having((e) => e.code, 'code', 'ATTENDANCE_CLOSED'),
      ),
    );
  });

  test('a missing event is reported with its status', () async {
    adapter
      ..statusCode = 404
      ..body = {'code': 'NOT_FOUND'};

    await expectLater(
      api.fetchAttendance('event-1'),
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

const _sheet = {
  'eventId': 'event-1',
  'eventKind': 'CLASS',
  'eventStatus': 'SCHEDULED',
  'markingOpensAt': '2026-11-03T17:00:00Z',
  'markingClosesAt': '2026-11-04T06:00:00Z',
  'markingOpen': true,
  'participants': <Object>[],
};
