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

  test('joins the waiting list with the chosen pass and leaves it', () async {
    adapter
      ..status = 201
      ..body = waitlistEntryJson(profileId: 'child', position: 2);
    final entry = await api.joinWaitlist(detailsQuery, 'pass-2');
    expect(adapter.request?.method, 'POST');
    expect(
      adapter.request?.path,
      '/api/v1/me/profiles/child/group-classes/class-1/waitlist-entries',
    );
    expect(adapter.request?.data, {'customerPassId': 'pass-2'});
    expect(entry.id, 'waitlist-1');
    expect(entry.customerId, 'child');
    expect(entry.isWaiting, isTrue);
    expect(entry.position, 2);
    expect(entry.customerPassId, 'pass-1');
    expect(entry.joinedAt, DateTime.utc(2030, 11, 1, 10).toLocal());

    adapter
      ..status = 200
      ..body = waitlistEntryJson(profileId: 'child', waiting: false);
    final left = await api.leaveWaitlist(scope, entry);
    expect(adapter.request?.method, 'POST');
    expect(
      adapter.request?.path,
      '/api/v1/me/profiles/child/waitlist-entries/waitlist-1/cancel',
    );
    expect(adapter.request?.data, isNull);
    expect(left.status, 'CANCELLED');
    expect(left.isWaiting, isFalse);
    expect(left.position, isNull);
  });

  test('confirms an offered place and reads its deadline', () async {
    adapter.body = offeredClassJson();
    final event = await api.fetchClass(detailsQuery);
    final offer = event.waitlist.ownEntry!;
    expect(event.isWaitlisted, isTrue);
    expect(event.waitlist.confirmationMinutes, 30);
    expect(offer.isOffered, isTrue);
    expect(offer.isWaiting, isFalse);
    expect(offer.position, isNull);
    expect(offer.offerExpiresAt, DateTime.utc(2030, 11, 2, 16).toLocal());

    adapter.body = {
      ...waitlistEntryJson(profileId: 'child', waiting: false),
      'status': 'PROMOTED',
      'reservationId': 'reservation-1',
    };
    final confirmed = await api.confirmWaitlistOffer(scope, offer);
    expect(adapter.request?.method, 'POST');
    expect(
      adapter.request?.path,
      '/api/v1/me/profiles/child/waitlist-entries/waitlist-1/confirm',
    );
    expect(adapter.request?.data, isNull);
    expect(confirmed.status, 'PROMOTED');
    expect(confirmed.isOpen, isFalse);
  });

  test('preserves stable server reason when the class is not full', () async {
    adapter
      ..status = 409
      ..body = {'code': 'CLASS_NOT_FULL', 'detail': 'Internal English text'};
    await expectLater(
      api.joinWaitlist(detailsQuery, 'pass-1'),
      throwsA(
        isA<ClassBookingException>()
            .having((e) => e.code, 'code', 'CLASS_NOT_FULL')
            .having((e) => e.statusCode, 'status', 409),
      ),
    );
  });

  test('reads the own waiting place and the option to join', () async {
    adapter.body = {
      'items': [
        fullClassJson(
          waitlistPosition: 2,
          waitingCount: 3,
          promotionBlockedReason: 'NO_ELIGIBLE_PASS',
        ),
        fullClassJson(waitingCount: 1),
      ],
    };
    final [waiting, open] = await api.fetchClasses(query);
    expect(waiting.isWaitlisted, isTrue);
    expect(waiting.waitlist.allowed, isFalse);
    expect(waiting.waitlist.reason, 'WAITLIST_ALREADY_JOINED');
    expect(waiting.waitlist.waitingCount, 3);
    expect(waiting.waitlist.ownEntry?.position, 2);
    expect(
      waiting.waitlist.ownEntry?.promotionBlockedReason,
      'NO_ELIGIBLE_PASS',
    );
    expect(open.isWaitlisted, isFalse);
    expect(open.waitlist.allowed, isTrue);
    expect(open.waitlist.reason, isNull);
    expect(open.waitlist.waitingCount, 1);
    expect(open.waitlist.ownEntry, isNull);
  });

  test(
    'class without waiting list data behaves as if it was disabled',
    () async {
      adapter.body = groupClassJson(availablePlaces: 0)..remove('waitlist');
      final event = await api.fetchClass(detailsQuery);
      expect(event.isWaitlisted, isFalse);
      expect(event.waitlist.allowed, isFalse);
      expect(event.waitlist.reason, 'WAITLIST_DISABLED');
      expect(event.waitlist.waitingCount, 0);
      expect(event.waitlist.ownEntry, isNull);
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
