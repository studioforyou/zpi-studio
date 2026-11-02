import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

import 'group_class_fixtures.dart';

class _Adapter implements HttpClientAdapter {
  Object body = {'items': []};
  int status = 200;
  RequestOptions? request;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    request = options;
    return ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  const scope = (accountId: 'account', profileId: 'child');
  const detailsQuery = (scope: scope, eventId: 'class-1');
  final query = (
    scope: scope,
    window: (from: DateTime(2030, 11, 2), to: DateTime(2030, 11, 9)),
  );
  late Dio dio;
  late _Adapter adapter;
  late GroupClassesApi api;

  setUp(() {
    adapter = _Adapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = GroupClassesApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test(
    'loads selected participant offer in UTC with actual availability',
    () async {
      adapter.body = {
        'items': [
          groupClassJson(availablePlaces: 0),
          groupClassJson(availablePlaces: null),
        ],
      };
      final events = await api.fetchClasses(query);
      expect(adapter.request?.path, '/api/v1/me/profiles/child/group-classes');
      expect(adapter.request?.queryParameters, {
        'from': query.window.from.toUtc().toIso8601String(),
        'to': query.window.to.toUtc().toIso8601String(),
      });
      expect(events.first.isFull, isTrue);
      expect(events.last.isFull, isFalse);
      expect(events.last.availablePlaces, isNull);
      expect(events.first.staff.single.displayName, 'Anna Trener');
      expect(events.first.roomNames, ['Sala Turkusowa']);
      expect(events.first.calendarColor, '#DA70A6');
      expect(events.first.serviceTypeId, 'service-1');
      expect(events.first.startsAt, DateTime.utc(2030, 11, 2, 17).toLocal());
    },
  );

  test(
    'loads own active reservations and class details independently',
    () async {
      adapter.body = {
        'items': [groupClassJson(profileId: 'child', reserved: true)],
      };
      final [event] = await api.fetchReservations(query);
      expect(adapter.request?.path, '/api/v1/me/profiles/child/reservations');
      expect(event.ownReservation?.customerId, 'child');
      adapter.body = groupClassJson();
      await api.fetchClass(detailsQuery);
      expect(
        adapter.request?.path,
        '/api/v1/me/profiles/child/group-classes/class-1',
      );
    },
  );

  test(
    'sends explicit pass selection and accepted cancellation consequences',
    () async {
      adapter.body = reservationJson(profileId: 'child');
      final reservation = await api.reserve(detailsQuery, 'pass-2');
      expect(adapter.request?.method, 'POST');
      expect(
        adapter.request?.path,
        '/api/v1/me/profiles/child/group-classes/class-1/reservations',
      );
      expect(adapter.request?.data, {'customerPassId': 'pass-2'});
      adapter.body = reservationJson(profileId: 'child', active: false);
      final cancelled = await api.cancel(scope, reservation);
      expect(
        adapter.request?.path,
        '/api/v1/me/profiles/child/reservations/reservation-1/cancel',
      );
      expect(adapter.request?.data, {
        'expectedVersion': 3,
        'expectedEntryReturned': true,
      });
      expect(cancelled.entryReturned, isTrue);
      expect(cancelled.isActive, isFalse);
    },
  );

  test(
    'preserves stable server reason when cancellation deadline changes',
    () async {
      adapter.status = 409;
      adapter.body = {
        'code': 'CANCELLATION_CONSEQUENCE_CHANGED',
        'detail': 'Internal English text',
      };
      await expectLater(
        api.cancel(scope, ClassReservation.fromJson(reservationJson())),
        throwsA(
          isA<ClassBookingException>()
              .having((e) => e.code, 'code', 'CANCELLATION_CONSEQUENCE_CHANGED')
              .having((e) => e.statusCode, 'status', 409),
        ),
      );
    },
  );

  test(
    'legacy reservation without pass or forecast remains readable',
    () async {
      adapter.body = {
        ...groupClassJson(reserved: true),
        'ownReservation': {
          ...reservationJson(),
          'customerPassId': null,
          'cancellation': {
            'allowed': false,
            'reason': 'CANCELLATION_TERMS_UNAVAILABLE',
            'entryReturned': null,
          },
        },
      };
      final event = await api.fetchClass(detailsQuery);
      expect(event.ownReservation?.customerPassId, isNull);
      expect(event.ownReservation?.cancellation.allowed, isFalse);
      expect(event.ownReservation?.cancellation.entryReturned, isNull);
    },
  );

  test('loads participant names and selected profile marker', () async {
    adapter.body = {
      'items': [
        {'displayName': 'Jan Klient', 'isSelf': true},
        {'displayName': 'Maria Nowak', 'isSelf': false},
      ],
    };
    final participants = await api.fetchParticipants(detailsQuery);
    expect(
      adapter.request?.path,
      '/api/v1/me/profiles/child/group-classes/class-1/participants',
    );
    expect(participants.map((p) => p.displayName), [
      'Jan Klient',
      'Maria Nowak',
    ]);
    expect(participants.map((p) => p.isSelf), [true, false]);
  });
}
