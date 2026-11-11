import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/accounts/data/staff_profile_api.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/schedule/data/availability_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/presentation/availability_screen.dart';

const _session = Session(
  accountId: 'account-1',
  displayName: 'Anna Nowak',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [],
  activeProfileId: null,
);

class _SignedInRepository implements AuthRepository {
  @override
  Future<Session?> restore() async => _session;

  @override
  Future<Session> login({
    required String email,
    required String password,
  }) async => _session;

  @override
  Future<void> logout() async {}

  @override
  Future<void> sendPasswordReset(String email) async {}
}

/// A Wednesday: Monday and Tuesday of the week are already past.
final _today = DateTime(2030, 11, 6);

Map<String, dynamic> _weekly(
  String id,
  int weekday,
  String start,
  String end, {
  String? validTo,
}) => {
  'id': id,
  'type': 'WEEKLY',
  'weekday': weekday,
  'date': null,
  'startTime': start,
  'endTime': end,
  'validFrom': '2026-01-05',
  'validTo': validTo,
};

Map<String, dynamic> _oneOff(
  String id,
  String date,
  int weekday,
  String start,
  String end,
) => {
  'id': id,
  'type': 'ONE_OFF',
  'weekday': weekday,
  'date': date,
  'startTime': start,
  'endTime': end,
  'validFrom': date,
  'validTo': date,
};

typedef _Request = ({
  String method,
  String path,
  Map<String, dynamic> query,
  Object? data,
});

/// Keeps the trainer's slots in memory and records every change request.
class _AvailabilityAdapter implements HttpClientAdapter {
  int listStatus = 200;
  int changeStatus = 200;
  int listCount = 0;
  final requests = <_Request>[];
  List<Map<String, dynamic>> slots = [
    _weekly('slot-mon', 1, '08:00', '10:00'),
    _weekly('slot-thu', 4, '17:00', '21:00'),
    _weekly('slot-fri', 5, '08:00', '10:00', validTo: '2030-11-15'),
    _oneOff('slot-day', '2030-11-09', 6, '09:00', '12:00'),
  ];

  ResponseBody _json(Object body, int status) => ResponseBody.fromString(
    jsonEncode(body),
    status,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    // The trainer shell opens on the schedule tab.
    if (options.path == ownScheduleEventsPath ||
        options.path == scheduleServiceTypesPath) {
      return _json({'items': []}, 200);
    }
    // The unread badge on the messages tab.
    if (options.path.startsWith('/api/v1/me/notifications')) {
      return _json({'count': 0}, 200);
    }
    if (options.path == staffProfilePath) {
      return _json({
        'id': 'staff-1',
        'firstName': 'Anna',
        'lastName': 'Nowak',
        'phone': null,
      }, 200);
    }
    if (options.method == 'GET') {
      listCount++;
      return listStatus == 200
          ? _json({'items': slots}, 200)
          : _json({'code': 'INTERNAL_ERROR'}, listStatus);
    }
    requests.add((
      method: options.method,
      path: options.path,
      query: options.queryParameters,
      data: options.data,
    ));
    if (changeStatus != 200) {
      return _json({'code': 'AVAILABILITY_OVERLAP'}, changeStatus);
    }
    final data = options.data as Map<String, dynamic>?;
    switch (options.method) {
      case 'POST':
        final added = [
          for (final weekday in (data!['weekdays'] as List?) ?? const [])
            _weekly(
              'slot-new-$weekday',
              weekday as int,
              data['startTime'] as String,
              data['endTime'] as String,
            ),
        ];
        slots = [...slots, ...added];
        return _json({'items': added}, 201);
      case 'DELETE':
        if (options.queryParameters.isEmpty) {
          slots = [
            for (final slot in slots)
              if (!options.path.endsWith('/${slot['id']}')) slot,
          ];
        }
        return ResponseBody.fromString('', 204);
      default:
        return _json(slots.first, 200);
    }
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _AvailabilityAdapter adapter;
  late Dio dio;

  setUp(() {
    adapter = _AvailabilityAdapter();
    dio = Dio()..httpClientAdapter = adapter;
  });

  tearDown(() => dio.close(force: true));

  Future<void> openAvailability(WidgetTester tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(_SignedInRepository()),
          dioProvider.overrideWithValue(dio),
          // The schedule tab's number asks for changes; series_change_test covers it.
          scheduleChangesProvider.overrideWith(
            (ref, owner) => Stream.value([]),
          ),
          availabilityTodayProvider.overrideWithValue(_today),
          unreadMessagesProvider.overrideWith(
            (ref, accountId) => Stream.value(0),
          ),
        ],
        child: const App(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(
      find.descendant(
        of: find.byType(NavigationBar),
        matching: find.text('Profil'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Dyspozycyjność'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Dyspozycyjność'));
    await tester.pumpAndSettle();
  }

  Finder bar(String id, String date) =>
      find.byKey(Key('availability-bar-$id-$date'));

  Finder inSheet(Finder finder) => find.descendant(
    of: find.byType(AvailabilityEditorSheet),
    matching: finder,
  );

  Future<void> tapAndSettle(WidgetTester tester, Finder finder) async {
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  testWidgets('shows a week of hours and follows their period', (tester) async {
    await openAvailability(tester);

    expect(find.byType(AvailabilityScreen), findsOneWidget);
    expect(find.text('4.11 – 10.11'), findsOneWidget);
    expect(find.text('Co tydzień'), findsOneWidget);
    expect(find.text('Tylko ten tydzień'), findsOneWidget);
    // Monday is already past.
    expect(bar('slot-mon', '2030-11-04'), findsNothing);
    expect(bar('slot-thu', '2030-11-07'), findsOneWidget);
    expect(bar('slot-fri', '2030-11-08'), findsOneWidget);
    expect(bar('slot-day', '2030-11-09'), findsOneWidget);
    final previous = find.widgetWithIcon(IconButton, Icons.chevron_left);
    expect(tester.widget<IconButton>(previous).onPressed, isNull);

    await tapAndSettle(tester, find.byTooltip('Następny tydzień'));
    expect(find.text('11.11 – 17.11'), findsOneWidget);
    expect(bar('slot-mon', '2030-11-11'), findsOneWidget);
    expect(bar('slot-fri', '2030-11-15'), findsOneWidget);
    expect(bar('slot-day', '2030-11-16'), findsNothing);

    // The Friday hours end on 15.11.
    await tapAndSettle(tester, find.byTooltip('Następny tydzień'));
    expect(bar('slot-thu', '2030-11-21'), findsOneWidget);
    expect(bar('slot-fri', '2030-11-22'), findsNothing);

    await tapAndSettle(tester, find.byTooltip('Poprzedni tydzień'));
    expect(find.text('11.11 – 17.11'), findsOneWidget);
  });

  testWidgets('a tapped bar shows its hours and actions until tapped again', (
    tester,
  ) async {
    await openAvailability(tester);
    expect(find.byTooltip('Usuń'), findsNothing);

    await tapAndSettle(tester, bar('slot-thu', '2030-11-07'));
    expect(find.text('17:00–21:00'), findsOneWidget);
    expect(find.byTooltip('Usuń'), findsOneWidget);
    expect(find.byTooltip('Zmień godziny'), findsOneWidget);

    await tapAndSettle(tester, bar('slot-thu', '2030-11-07'));
    expect(find.text('17:00–21:00'), findsNothing);
    expect(find.byTooltip('Usuń'), findsNothing);
  });

  testWidgets('adds weekly hours on the chosen days', (tester) async {
    await openAvailability(tester);
    await tapAndSettle(tester, find.byTooltip('Dodaj godziny'));

    expect(find.text('Nowe godziny'), findsOneWidget);
    expect(find.text('Bez daty końcowej'), findsOneWidget);
    await tapAndSettle(tester, find.text('Zapisz'));
    expect(find.text('Wybierz co najmniej jeden dzień.'), findsOneWidget);
    expect(adapter.requests, isEmpty);

    await tapAndSettle(tester, inSheet(find.text('Pn')));
    await tapAndSettle(tester, inSheet(find.text('Śr')));
    await tapAndSettle(tester, find.text('Zapisz'));

    expect(adapter.requests.single.method, 'POST');
    expect(adapter.requests.single.path, myAvailabilityPath);
    expect(adapter.requests.single.data, {
      'weekdays': [1, 3],
      'startTime': '16:00',
      'endTime': '20:00',
      'validTo': null,
    });
    expect(adapter.listCount, 2);
    expect(find.byType(AvailabilityEditorSheet), findsNothing);
    expect(find.text('Dyspozycyjność została zapisana.'), findsOneWidget);
    expect(bar('slot-new-3', '2030-11-06'), findsOneWidget);
  });

  testWidgets('adds hours on days of the shown week only', (tester) async {
    await openAvailability(tester);
    await tapAndSettle(tester, find.byTooltip('Dodaj godziny'));
    await tapAndSettle(tester, find.text('Ten tydzień'));

    expect(find.text('Nowe godziny: 4.11 – 10.11'), findsOneWidget);
    expect(find.text('Obowiązuje do'), findsNothing);
    // Monday and Tuesday are past and cannot be chosen.
    await tapAndSettle(tester, inSheet(find.text('Pn')));
    await tapAndSettle(tester, inSheet(find.text('So')));
    await tapAndSettle(tester, inSheet(find.text('Nd')));
    await tapAndSettle(tester, find.text('Zapisz'));

    expect(adapter.requests.single.data, {
      'dates': ['2030-11-09', '2030-11-10'],
      'startTime': '16:00',
      'endTime': '20:00',
    });
  });

  testWidgets('keeps the sheet open with a message when hours overlap', (
    tester,
  ) async {
    adapter.changeStatus = 409;
    await openAvailability(tester);
    await tapAndSettle(tester, find.byTooltip('Dodaj godziny'));
    await tapAndSettle(tester, inSheet(find.text('Cz')));
    await tapAndSettle(tester, find.text('Zapisz'));

    expect(find.byType(AvailabilityEditorSheet), findsOneWidget);
    expect(
      find.text('Te godziny nachodzą na inne godziny tego dnia.'),
      findsOneWidget,
    );
    expect(adapter.listCount, 1);
  });

  testWidgets('changes weekly hours on one day or every week', (tester) async {
    await openAvailability(tester);
    await tapAndSettle(tester, bar('slot-thu', '2030-11-07'));
    await tapAndSettle(tester, find.byTooltip('Zmień godziny'));

    expect(inSheet(find.text('Zmień godziny')), findsOneWidget);
    expect(find.text('Bez daty końcowej'), findsOneWidget);
    await tapAndSettle(tester, find.text('Ten tydzień'));
    await tapAndSettle(tester, find.text('Zapisz'));
    expect(adapter.requests.last.method, 'PATCH');
    expect(adapter.requests.last.path, '$myAvailabilityPath/slot-thu');
    expect(adapter.requests.last.query, {'date': '2030-11-07'});
    expect(adapter.requests.last.data, {
      'startTime': '17:00',
      'endTime': '21:00',
    });

    await tapAndSettle(tester, bar('slot-fri', '2030-11-08'));
    await tapAndSettle(tester, find.byTooltip('Zmień godziny'));
    expect(find.text('15 listopada 2030'), findsOneWidget);
    await tapAndSettle(tester, find.byTooltip('Usuń datę końcową'));
    await tapAndSettle(tester, find.text('Zapisz'));
    expect(adapter.requests.last.path, '$myAvailabilityPath/slot-fri');
    expect(adapter.requests.last.query, isEmpty);
    expect(adapter.requests.last.data, {
      'startTime': '08:00',
      'endTime': '10:00',
      'validTo': null,
    });
  });

  testWidgets('a single day can be made to repeat every week', (tester) async {
    await openAvailability(tester);
    await tapAndSettle(tester, bar('slot-day', '2030-11-09'));
    await tapAndSettle(tester, find.byTooltip('Zmień godziny'));

    expect(find.text('Obowiązuje do'), findsNothing);
    await tapAndSettle(tester, find.text('Każdy tydzień'));
    expect(find.text('Obowiązuje do'), findsOneWidget);
    await tapAndSettle(tester, find.text('Zapisz'));
    expect(adapter.requests.single.query, isEmpty);
    expect(adapter.requests.single.data, {
      'startTime': '09:00',
      'endTime': '12:00',
      'validTo': null,
    });
  });

  testWidgets('removes hours on one day or every week', (tester) async {
    await openAvailability(tester);
    await tapAndSettle(tester, bar('slot-thu', '2030-11-07'));
    await tapAndSettle(tester, find.byTooltip('Usuń'));

    expect(find.text('Usunąć dyspozycyjność?'), findsOneWidget);
    expect(find.text('Każdy czwartek'), findsOneWidget);
    await tapAndSettle(tester, find.text('Ten dzień'));
    expect(adapter.requests.last.method, 'DELETE');
    expect(adapter.requests.last.path, '$myAvailabilityPath/slot-thu');
    expect(adapter.requests.last.query, {'date': '2030-11-07'});
    expect(find.text('Godziny zostały usunięte.'), findsOneWidget);

    await tapAndSettle(tester, bar('slot-fri', '2030-11-08'));
    await tapAndSettle(tester, find.byTooltip('Usuń'));
    await tapAndSettle(tester, find.text('Każdy piątek'));
    expect(adapter.requests.last.path, '$myAvailabilityPath/slot-fri');
    expect(adapter.requests.last.query, isEmpty);
    expect(bar('slot-fri', '2030-11-08'), findsNothing);

    await tapAndSettle(tester, bar('slot-day', '2030-11-09'));
    await tapAndSettle(tester, find.byTooltip('Usuń'));
    expect(find.textContaining('Każd'), findsNothing);
    await tapAndSettle(tester, find.text('Anuluj'));
    expect(adapter.requests, hasLength(2));
  });

  testWidgets('a failed load offers retry', (tester) async {
    adapter.listStatus = 500;
    await openAvailability(tester);
    expect(find.text('Nie udało się pobrać dyspozycyjności.'), findsOneWidget);

    adapter.listStatus = 200;
    await tapAndSettle(tester, find.text('Spróbuj ponownie'));
    expect(bar('slot-thu', '2030-11-07'), findsOneWidget);
  });

  testWidgets('fits a small phone with large text', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await openAvailability(tester);
    expect(tester.takeException(), isNull);
    expect(find.byTooltip('Dodaj godziny').hitTestable(), findsOneWidget);

    final thursday = bar('slot-thu', '2030-11-07');
    await tester.ensureVisible(thursday);
    await tester.pumpAndSettle();
    await tapAndSettle(tester, thursday);
    await tapAndSettle(tester, find.byTooltip('Zmień godziny'));
    await tester.ensureVisible(find.text('Zapisz'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Zapisz').hitTestable(), findsOneWidget);
  });

  test('parses slots and tells the days they apply on', () {
    final weekly = AvailabilitySlot.fromJson(
      _weekly('id', 3, '06:30', '09:05', validTo: '2030-02-13'),
    );
    expect(weekly.isWeekly, isTrue);
    expect(weekly.start, const TimeOfDay(hour: 6, minute: 30));
    expect(formatTime(weekly.end), '09:05');
    expect(formatDate(weekly.validFrom), '2026-01-05');
    expect(weekly.appliesOn(DateTime(2030, 2, 13)), isTrue);
    expect(weekly.appliesOn(DateTime(2030, 2, 14)), isFalse);
    expect(weekly.appliesOn(DateTime(2030, 2, 20)), isFalse);
    expect(weekly.appliesOn(DateTime(2025, 12, 31)), isFalse);

    final day = AvailabilitySlot.fromJson(
      _oneOff('id', '2030-11-09', 6, '09:00', '12:00'),
    );
    expect(day.isWeekly, isFalse);
    expect(day.appliesOn(DateTime(2030, 11, 9)), isTrue);
    expect(day.appliesOn(DateTime(2030, 11, 16)), isFalse);
    expect(weekRange(mondayOf(DateTime(2030, 11, 9))), '4.11 – 10.11');
  });
}
