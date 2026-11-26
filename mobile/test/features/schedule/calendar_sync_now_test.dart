import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/calendar_events_api.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';
import 'package:mobile/features/schedule/data/calendar_sync_now.dart';
import 'package:mobile/features/schedule/data/phone_calendar.dart';
import 'package:mobile/features/schedule/presentation/calendar_sync_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

final _now = DateTime.utc(2026, 11, 26, 12);

CalendarEntry _entry(
  String uid, {
  int inDays = 1,
  String title = 'Pole dance',
  bool cancelled = false,
  String? location = 'Sala 1',
}) => CalendarEntry(
  uid: uid,
  startsAt: _now.add(Duration(days: inDays)),
  endsAt: _now.add(Duration(days: inDays, hours: 1)),
  title: title,
  location: location,
  description: 'Prowadzi: Anna Kowalska',
  cancelled: cancelled,
  sequence: 1,
);

/// A calendar on the phone kept in memory.
class _FakePhone implements PhoneCalendar {
  bool access = true;
  final events = <String, (String, PhoneEventData)>{};
  final calendars = const [
    PhoneCalendarInfo(id: 'local', name: 'Na telefonie'),
    PhoneCalendarInfo(
      id: 'google',
      name: 'anna@example.com',
      accountName: 'anna@example.com',
      isPrimary: true,
    ),
  ];
  int _next = 0;
  int updates = 0;

  List<PhoneEventData> inCalendar(String calendarId) => [
    for (final (calendar, data) in events.values)
      if (calendar == calendarId) data,
  ];

  @override
  Future<bool> requestAccess() async => access;

  @override
  Future<void> openSettings() async {}

  @override
  Future<List<PhoneCalendarInfo>> writableCalendars() async => calendars;

  @override
  Future<bool> exists(String eventId) async => events.containsKey(eventId);

  @override
  Future<String> create(String calendarId, PhoneEventData event) async {
    final id = 'event-${_next++}';
    events[id] = (calendarId, event);
    return id;
  }

  @override
  Future<void> update(String eventId, PhoneEventData event) async {
    updates++;
    events[eventId] = (events[eventId]!.$1, event);
  }

  @override
  Future<void> delete(String eventId) async => events.remove(eventId);
}

class _FakeEventsApi implements CalendarEventsApi {
  List<CalendarEntry> entries = [];
  final scopes = <CalendarFeedScope>[];

  @override
  Future<List<CalendarEntry>> fetch(CalendarFeedScope scope) async {
    scopes.add(scope);
    return entries;
  }
}

class _FakeFeedApi implements CalendarFeedApi {
  @override
  Future<CalendarFeedStatus> fetchStatus() async =>
      const CalendarFeedStatus(active: false, createdAt: null);

  @override
  Future<CalendarFeedLink> create() => throw UnimplementedError();

  @override
  Future<void> turnOff() => throw UnimplementedError();
}

class _Auth extends AuthController {
  _Auth(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

Session _session(List<AppRole> roles) => Session(
  accountId: 'account-1',
  displayName: 'Anna Kowalska',
  roles: roles,
  activeRole: roles.first,
  staffProfileId: roles.contains(AppRole.trainer) ? 'staff-1' : null,
  profiles: const [],
  activeProfileId: null,
);

void main() {
  test('reads an entry of the backend', () {
    final entry = CalendarEntry.fromJson({
      'uid': 'e1-p1@studioforyou.pl',
      'startsAt': '2026-11-27T17:00:00Z',
      'endsAt': '2026-11-27T18:00:00Z',
      'title': 'Zosia: Kids acrobatics',
      'location': null,
      'description': 'Prowadzi: Anna Kowalska',
      'cancelled': true,
      'sequence': 3,
    });
    expect(entry.title, 'Zosia: Kids acrobatics');
    expect(entry.startsAt, DateTime.utc(2026, 11, 27, 17));
    expect(entry.location, isNull);
    expect(entry.cancelled, isTrue);
    expect(entry.sequence, 3);
  });

  group('CalendarSyncer', () {
    late _FakePhone phone;
    late CalendarSyncer syncer;
    late Map<String, SyncedEvent> synced;

    setUp(() {
      phone = _FakePhone();
      syncer = CalendarSyncer(phone);
      synced = {};
    });

    Future<CalendarSyncResult> sync(List<CalendarEntry> entries) => syncer.run(
      calendarId: 'google',
      entries: entries,
      events: synced,
      cancelledPrefix: 'Odwołane · ',
      now: _now,
    );

    test('adds upcoming events but not past or cancelled ones', () async {
      final result = await sync([
        _entry('a'),
        _entry('past', inDays: -3),
        _entry('cancelled', cancelled: true),
      ]);

      expect(result.added, 1);
      expect(phone.inCalendar('google').single.title, 'Pole dance');
      expect(synced.keys, ['a']);
    });

    test('syncing again adds nothing twice and updates what changed', () async {
      await sync([_entry('a'), _entry('b', inDays: 2)]);

      final again = await sync([_entry('a'), _entry('b', inDays: 2)]);
      expect(again.unchanged, isTrue);
      expect(phone.events, hasLength(2));
      expect(phone.updates, 0);

      final moved = await sync([
        _entry('a', location: 'Sala 2'),
        _entry('b', inDays: 2),
      ]);
      expect(moved.updated, 1);
      expect(moved.added, 0);
      expect(phone.events, hasLength(2));
      expect(
        phone.inCalendar('google').map((e) => e.location),
        containsAll(['Sala 2', 'Sala 1']),
      );
    });

    test('an event the studio cancelled stays as cancelled and free', () async {
      await sync([_entry('a')]);

      final result = await sync([_entry('a', cancelled: true)]);

      expect(result.updated, 1);
      final event = phone.inCalendar('google').single;
      expect(event.title, 'Odwołane · Pole dance');
      expect(event.busy, isFalse);
    });

    test(
      'removes upcoming events that left the schedule, keeps past ones',
      () async {
        await sync([_entry('a'), _entry('soon', inDays: 1)]);
        // "soon" has started since: it is kept, only forgotten.
        synced['soon'] = SyncedEvent(
          eventId: synced['soon']!.eventId,
          fingerprint: synced['soon']!.fingerprint,
          startsAt: _now.subtract(const Duration(hours: 1)),
        );

        final result = await sync([]);

        expect(result.removed, 1);
        expect(phone.events, hasLength(1));
        expect(synced, isEmpty);
      },
    );

    test('adds again an event the user deleted from the calendar', () async {
      await sync([_entry('a')]);
      phone.events.clear();

      final result = await sync([_entry('a')]);

      expect(result.added, 1);
      expect(phone.events, hasLength(1));
    });
  });

  group('Synchronizuj teraz', () {
    late _FakePhone phone;
    late _FakeEventsApi api;

    setUp(() {
      FlutterSecureStorage.setMockInitialValues({});
      phone = _FakePhone();
      api = _FakeEventsApi()..entries = [_entry('a'), _entry('b', inDays: 3)];
    });

    Future<void> pump(WidgetTester tester, List<AppRole> roles) async {
      tester.view.physicalSize = const Size(800, 1800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        ProviderScope(
          retry: (_, _) => null,
          overrides: [
            authControllerProvider.overrideWith(() => _Auth(_session(roles))),
            calendarFeedApiProvider.overrideWithValue(_FakeFeedApi()),
            calendarEventsApiProvider.overrideWithValue(api),
            phoneCalendarProvider.overrideWithValue(phone),
            calendarSyncClockProvider.overrideWithValue(() => _now),
          ],
          child: const MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            locale: Locale('pl'),
            home: CalendarSyncScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets(
      'a client picks a calendar once and syncs again without asking',
      (tester) async {
        await pump(tester, [AppRole.client]);

        await tester.tap(find.byKey(const Key('calendarSyncNow')));
        await tester.pumpAndSettle();
        expect(find.text('Co dodać do kalendarza?'), findsNothing);
        expect(
          find.text('W którym kalendarzu zapisać zajęcia?'),
          findsOneWidget,
        );
        await tester.tap(
          find.byKey(const Key('calendarSyncNowCalendar.google')),
        );
        await tester.pumpAndSettle();

        expect(phone.inCalendar('google'), hasLength(2));
        expect(api.scopes, [CalendarFeedScope.all]);
        expect(
          find.text('Dodane: 2, zaktualizowane: 0, usunięte: 0.'),
          findsOneWidget,
        );
        expect(find.byKey(const Key('calendarSyncNowLast')), findsOneWidget);

        api.entries = [_entry('a')];
        await tester.tap(find.byKey(const Key('calendarSyncNow')));
        await tester.pumpAndSettle();
        expect(find.text('W którym kalendarzu zapisać zajęcia?'), findsNothing);
        expect(phone.inCalendar('google'), hasLength(1));
      },
    );

    testWidgets('an account with both roles chooses what to add', (
      tester,
    ) async {
      await pump(tester, [AppRole.trainer, AppRole.client]);

      await tester.tap(find.byKey(const Key('calendarSyncNow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('calendarSyncScope.trainer')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('calendarSyncNowCalendar.local')));
      await tester.pumpAndSettle();

      expect(api.scopes, [CalendarFeedScope.trainer]);
      expect(phone.inCalendar('local'), hasLength(2));
    });

    testWidgets('changing the calendar moves the upcoming events', (
      tester,
    ) async {
      await pump(tester, [AppRole.client]);
      await tester.tap(find.byKey(const Key('calendarSyncNow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('calendarSyncNowCalendar.google')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('calendarSyncNowChange')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('calendarSyncNowCalendar.local')));
      await tester.pumpAndSettle();

      expect(phone.inCalendar('google'), isEmpty);
      expect(phone.inCalendar('local'), hasLength(2));
    });

    testWidgets('removing asks first and deletes the added events', (
      tester,
    ) async {
      await pump(tester, [AppRole.client]);
      await tester.tap(find.byKey(const Key('calendarSyncNow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('calendarSyncNowCalendar.google')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('calendarSyncNowRemove')));
      await tester.pumpAndSettle();
      expect(find.text('Usunąć dodane zajęcia?'), findsOneWidget);
      await tester.tap(find.byKey(const Key('calendarSyncConfirm')));
      await tester.pumpAndSettle();

      expect(phone.events, isEmpty);
      expect(find.byKey(const Key('calendarSyncNowRemove')), findsNothing);
    });

    testWidgets('without calendar access nothing is written', (tester) async {
      phone.access = false;
      await pump(tester, [AppRole.client]);

      await tester.tap(find.byKey(const Key('calendarSyncNow')));
      await tester.pumpAndSettle();

      expect(
        find.text('Aplikacja nie ma dostępu do kalendarza.'),
        findsOneWidget,
      );
      expect(phone.events, isEmpty);
      expect(api.scopes, isEmpty);
    });
  });
}
