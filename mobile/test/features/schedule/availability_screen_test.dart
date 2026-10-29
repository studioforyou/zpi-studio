import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/accounts/data/staff_profile_api.dart';
import 'package:mobile/features/schedule/data/availability_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
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

Map<String, dynamic> _weekly(
  String id,
  int weekday,
  String start,
  String end,
) => {
  'id': id,
  'type': 'WEEKLY',
  'weekday': weekday,
  'date': null,
  'startTime': start,
  'endTime': end,
  'validFrom': '2026-01-05',
  'validTo': null,
};

/// Keeps the trainer's slots in memory and records every change request.
class _AvailabilityAdapter implements HttpClientAdapter {
  int listStatus = 200;
  int changeStatus = 200;
  int listCount = 0;
  final requests = <({String method, String path, Object? data})>[];
  List<Map<String, dynamic>> slots = [
    _weekly('slot-tue', 2, '17:00', '21:00'),
    {..._weekly('slot-thu', 4, '08:00', '10:00'), 'validTo': '2030-06-30'},
    {
      'id': 'slot-day',
      'type': 'ONE_OFF',
      'weekday': 6,
      'date': '2030-11-02',
      'startTime': '09:00',
      'endTime': '12:00',
      'validFrom': '2030-11-02',
      'validTo': '2030-11-02',
    },
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
    if (options.path == ownScheduleEventsPath) {
      return _json({'items': []}, 200);
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
      data: options.data,
    ));
    if (changeStatus != 200) {
      return _json({'code': 'AVAILABILITY_OVERLAP'}, changeStatus);
    }
    final data = options.data as Map<String, dynamic>?;
    switch (options.method) {
      case 'POST':
        slots = [
          ...slots,
          _weekly(
            'slot-new',
            data!['weekday'] as int,
            data['startTime'] as String,
            data['endTime'] as String,
          ),
        ];
        return _json(slots.last, 201);
      case 'DELETE':
        slots = [
          for (final slot in slots)
            if (!options.path.endsWith('/${slot['id']}')) slot,
        ];
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
    await tester.tap(find.text('Dyspozycyjność'));
    await tester.pumpAndSettle();
  }

  Finder addButtonOf(String day) => find.descendant(
    of: find.ancestor(of: find.text(day), matching: find.byType(Row)).first,
    matching: find.byTooltip('Dodaj godziny'),
  );

  testWidgets('shows the regular week and the extra days', (tester) async {
    await openAvailability(tester);

    expect(find.byType(AvailabilityScreen), findsOneWidget);
    for (final day in ['Poniedziałek', 'Wtorek', 'Środa', 'Niedziela']) {
      expect(find.text(day), findsOneWidget);
    }
    expect(find.text('Niedostępny'), findsNWidgets(5));
    expect(find.widgetWithText(ActionChip, '17:00–21:00'), findsOneWidget);
    expect(
      find.widgetWithText(ActionChip, '08:00–10:00 · do 30.06'),
      findsOneWidget,
    );
    await tester.scrollUntilVisible(find.text('09:00–12:00'), 100);
    expect(find.text('Sobota, 2 listopada'), findsOneWidget);
  });

  testWidgets('adds weekly hours and reloads the list', (tester) async {
    await openAvailability(tester);
    await tester.tap(addButtonOf('Poniedziałek'));
    await tester.pumpAndSettle();

    expect(find.text('Nowe godziny: Poniedziałek'), findsOneWidget);
    expect(find.text('Bez daty końcowej'), findsOneWidget);
    await tester.tap(find.text('Zapisz'));
    await tester.pumpAndSettle();

    expect(adapter.requests.single.method, 'POST');
    expect(adapter.requests.single.path, myAvailabilityPath);
    expect(adapter.requests.single.data, {
      'weekday': 1,
      'startTime': '16:00',
      'endTime': '20:00',
      'validTo': null,
    });
    expect(adapter.listCount, 2);
    expect(find.byType(AvailabilityEditorSheet), findsNothing);
    expect(find.widgetWithText(ActionChip, '16:00–20:00'), findsOneWidget);
    expect(find.text('Dyspozycyjność została zapisana.'), findsOneWidget);
    expect(find.text('Niedostępny'), findsNWidgets(4));
  });

  testWidgets('keeps the sheet open with a message when hours overlap', (
    tester,
  ) async {
    adapter.changeStatus = 409;
    await openAvailability(tester);
    await tester.tap(addButtonOf('Wtorek'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Zapisz'));
    await tester.pumpAndSettle();

    expect(find.byType(AvailabilityEditorSheet), findsOneWidget);
    expect(
      find.text('Te godziny nachodzą na inne godziny tego dnia.'),
      findsOneWidget,
    );
    expect(adapter.listCount, 1);
  });

  testWidgets('changes and removes existing hours', (tester) async {
    await openAvailability(tester);
    await tester.tap(find.widgetWithText(ActionChip, '08:00–10:00 · do 30.06'));
    await tester.pumpAndSettle();

    expect(find.text('Czwartek'), findsWidgets);
    expect(find.text('30 czerwca 2030'), findsOneWidget);
    await tester.tap(find.byTooltip('Usuń datę końcową'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Zapisz'));
    await tester.pumpAndSettle();
    expect(adapter.requests.last.method, 'PATCH');
    expect(adapter.requests.last.path, '$myAvailabilityPath/slot-thu');
    expect(adapter.requests.last.data, {
      'startTime': '08:00',
      'endTime': '10:00',
      'validTo': null,
    });

    await tester.tap(find.widgetWithText(ActionChip, '17:00–21:00'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Usuń'));
    await tester.tap(find.text('Usuń'));
    await tester.pumpAndSettle();
    expect(adapter.requests.last.method, 'DELETE');
    expect(adapter.requests.last.path, '$myAvailabilityPath/slot-tue');
    expect(find.widgetWithText(ActionChip, '17:00–21:00'), findsNothing);
    expect(find.text('Godziny zostały usunięte.'), findsOneWidget);
  });

  testWidgets('a single day is edited without an end date', (tester) async {
    await openAvailability(tester);
    await tester.scrollUntilVisible(find.text('09:00–12:00'), 100);
    await tester.tap(find.text('Sobota, 2 listopada'));
    await tester.pumpAndSettle();

    expect(find.text('Obowiązuje do'), findsNothing);
    await tester.tap(find.text('Zapisz'));
    await tester.pumpAndSettle();
    expect(adapter.requests.single.data, {
      'startTime': '09:00',
      'endTime': '12:00',
    });
  });

  testWidgets('a failed load offers retry', (tester) async {
    adapter.listStatus = 500;
    await openAvailability(tester);
    expect(find.text('Nie udało się pobrać dyspozycyjności.'), findsOneWidget);

    adapter.listStatus = 200;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(ActionChip, '17:00–21:00'), findsOneWidget);
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

    final list = find
        .descendant(
          of: find.byType(AvailabilityScreen),
          matching: find.byType(Scrollable),
        )
        .first;
    final chip = find.widgetWithText(ActionChip, '08:00–10:00 · do 30.06');
    await tester.scrollUntilVisible(chip, 100, scrollable: list);
    await tester.ensureVisible(chip);
    await tester.pumpAndSettle();
    await tester.tap(chip);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Usuń'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Usuń').hitTestable(), findsOneWidget);
  });

  test('parses slots from the API', () {
    final slot = AvailabilitySlot.fromJson({
      ..._weekly('id', 3, '06:30', '09:05'),
      'validTo': '2030-02-01',
    });
    expect(slot.isWeekly, isTrue);
    expect(slot.start, const TimeOfDay(hour: 6, minute: 30));
    expect(formatTime(slot.end), '09:05');
    expect(slot.validTo, DateTime(2030, 2, 1));
    expect(formatDate(slot.validFrom), '2026-01-05');
    expect(weekdayName(3), 'Środa');
  });
}
