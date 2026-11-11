import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/training_history_api.dart';
import 'package:mobile/features/trainings/presentation/training_history_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Map<String, dynamic> _entryJson({
  String id = 'reservation-1',
  String title = 'Pole dance',
  String startsAt = '2030-11-02T17:00:00Z',
  String outcome = 'PRESENT',
  bool? entryReturned,
}) => {
  'reservationId': id,
  'eventId': 'event-$id',
  'title': title,
  'serviceTypeName': 'Pole dance dla początkujących',
  'calendarColor': '#DA70A6',
  'startsAt': startsAt,
  'endsAt': startsAt.replaceFirst('T17', 'T18'),
  'staff': [
    {'staffId': 'staff-1', 'displayName': 'Anna Trener', 'role': 'LEAD'},
  ],
  'resources': [
    {'resourceId': 'room-1', 'name': 'Sala Turkusowa'},
  ],
  'outcome': outcome,
  'entryReturned': entryReturned,
};

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

const _session = Session(
  accountId: 'parent',
  displayName: 'Anna Klient',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'self', displayName: 'Anna Klient', isChild: false),
    Profile(id: 'child', displayName: 'Jan Klient', isChild: true),
  ],
  activeProfileId: 'self',
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
  void replace(Session session) => state = Authenticated(session);
}

class _Api implements TrainingHistoryApi {
  List<Map<String, dynamic>> items = [];
  List<Map<String, dynamic>> childItems = [];
  Future<List<TrainingHistoryEntry>> Function(ClassesQuery)? load;
  bool fails = false;
  final List<ClassesQuery> queries = [];

  @override
  Future<List<TrainingHistoryEntry>> fetchHistory(ClassesQuery query) async {
    queries.add(query);
    if (fails) throw const ClassBookingException(statusCode: 503);
    if (load != null) return load!(query);
    return [
      for (final item in query.scope.profileId == 'child' ? childItems : items)
        TrainingHistoryEntry.fromJson(item),
    ];
  }
}

void main() {
  group('TrainingHistoryApi', () {
    late Dio dio;
    late _Adapter adapter;

    setUp(() {
      adapter = _Adapter();
      dio = Dio()..httpClientAdapter = adapter;
    });

    tearDown(() => dio.close(force: true));

    test('loads the participant history in UTC with outcomes', () async {
      final query = (
        scope: (accountId: 'account', profileId: 'child'),
        window: (from: DateTime(2030, 11), to: DateTime(2030, 12)),
      );
      adapter.body = {
        'items': [
          _entryJson(),
          _entryJson(
            id: 'reservation-2',
            outcome: 'LATE_CANCELLED',
            entryReturned: false,
          ),
          _entryJson(id: 'reservation-3', outcome: 'SOMETHING_NEW'),
        ],
      };
      final entries = await TrainingHistoryApi(dio).fetchHistory(query);
      expect(
        adapter.request?.path,
        '/api/v1/me/profiles/child/training-history',
      );
      expect(adapter.request?.queryParameters, {
        'from': query.window.from.toUtc().toIso8601String(),
        'to': query.window.to.toUtc().toIso8601String(),
      });
      expect(entries.map((entry) => entry.outcome), [
        TrainingOutcome.present,
        TrainingOutcome.lateCancelled,
        TrainingOutcome.unmarked,
      ]);
      expect(entries[1].entryReturned, isFalse);
      expect(entries.first.staff.single.displayName, 'Anna Trener');
      expect(entries.first.roomNames, ['Sala Turkusowa']);
      expect(entries.first.startsAt, DateTime.utc(2030, 11, 2, 17).toLocal());
    });

    test('maps an error response to its stable code', () async {
      adapter
        ..status = 404
        ..body = {'code': 'NOT_FOUND'};
      final query = (
        scope: (accountId: 'account', profileId: 'self'),
        window: (from: DateTime(2030, 11), to: DateTime(2030, 12)),
      );
      await expectLater(
        TrainingHistoryApi(dio).fetchHistory(query),
        throwsA(
          isA<ClassBookingException>()
              .having((e) => e.statusCode, 'statusCode', 404)
              .having((e) => e.code, 'code', 'NOT_FOUND'),
        ),
      );
    });
  });

  group('TrainingHistoryScreen', () {
    final usageQueries = <PassUsageQuery>[];
    Future<ProviderContainer> pump(
      WidgetTester tester,
      _Api api, {
      bool settle = true,
      double width = 800,
      double scale = 1,
    }) async {
      usageQueries.clear();
      tester.view.physicalSize = Size(width, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final container = ProviderContainer(
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(_Auth.new),
          trainingHistoryApiProvider.overrideWithValue(api),
          passUsageProvider.overrideWith((ref, query) async {
            usageQueries.add(query);
            return const PassUsagePage(
              items: [],
              page: 0,
              totalItems: 0,
              totalPages: 0,
              participants: [],
              products: [],
            );
          }),
        ],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(scale)),
              child: child!,
            ),
            locale: const Locale('pl'),
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: TrainingHistoryScreen(today: DateTime(2030, 11, 20)),
          ),
        ),
      );
      if (settle) await tester.pumpAndSettle();
      return container;
    }

    testWidgets('summarizes the month and shows each outcome', (tester) async {
      final api = _Api()
        ..items = [
          _entryJson(),
          _entryJson(id: 'r2', title: 'Joga', outcome: 'ABSENT'),
          _entryJson(id: 'r3', title: 'Stretching'),
          _entryJson(id: 'r4', title: 'Pilates', outcome: 'PRESENT'),
          _entryJson(
            id: 'r5',
            title: 'Aerial',
            outcome: 'LATE_CANCELLED',
            entryReturned: false,
          ),
          _entryJson(id: 'r6', title: 'Hoop', outcome: 'CLASS_CANCELLED'),
        ];
      await pump(tester, api);

      expect(api.queries, hasLength(2));
      expect(api.queries.first.scope, (accountId: 'parent', profileId: 'self'));
      expect(api.queries.first.window, (
        from: DateTime(2030, 11),
        to: DateTime(2030, 12),
      ));
      expect(find.text('Listopad 2030'), findsOneWidget);
      expect(find.text('Wszyscy uczestnicy'), findsOneWidget);
      expect(find.text('Frekwencja: 75%'), findsOneWidget);
      expect(
        find.text('2'),
        findsOneWidget,
      ); // Late cancellation and studio cancellation.
      expect(find.text('Obecność'), findsNWidgets(3));
      expect(find.text('Nieobecność'), findsOneWidget);
      expect(find.text('Odwołano po terminie'), findsOneWidget);
      expect(
        find.text('Wejście z karnetu nie zostało zwrócone.'),
        findsOneWidget,
      );
      expect(find.text('Zajęcia odwołane przez studio'), findsOneWidget);
    });

    testWidgets('moves between past months but not into the future', (
      tester,
    ) async {
      final api = _Api();
      await pump(tester, api);

      expect(
        find.text('W tym miesiącu nie było treningów, na które się zapisano.'),
        findsOneWidget,
      );
      final next = find.ancestor(
        of: find.byTooltip('Następny miesiąc'),
        matching: find.byType(IconButton),
      );
      expect(tester.widget<IconButton>(next).onPressed, isNull);

      await tester.tap(find.byTooltip('Poprzedni miesiąc'));
      await tester.pumpAndSettle();
      expect(find.text('Październik 2030'), findsOneWidget);
      expect(api.queries.last.window, (
        from: DateTime(2030, 10),
        to: DateTime(2030, 11),
      ));

      await tester.tap(next);
      await tester.pumpAndSettle();
      expect(find.text('Listopad 2030'), findsOneWidget);
    });

    testWidgets('offers a retry when loading fails', (tester) async {
      final api = _Api()..fails = true;
      await pump(tester, api);
      expect(find.text('Spróbuj ponownie'), findsOneWidget);

      api
        ..fails = false
        ..items = [_entryJson()];
      await tester.tap(find.text('Spróbuj ponownie'));
      await tester.pumpAndSettle();
      expect(find.text('Obecność'), findsOneWidget);
    });

    testWidgets(
      'one history includes the child and filters attendance by participant',
      (tester) async {
        final api = _Api()
          ..items = [_entryJson(title: 'Zajęcia rodzica')]
          ..childItems = [
            _entryJson(
              id: 'child-reservation',
              title: 'Zajęcia dziecka',
              startsAt: '2030-11-03T17:00:00Z',
              outcome: 'ABSENT',
            ),
          ];
        await pump(tester, api);
        expect(find.text('Frekwencja: 50%'), findsOneWidget);
        expect(find.text('Anna Klient'), findsOneWidget);
        expect(find.text('Jan Klient'), findsOneWidget);
        expect(
          tester.getTopLeft(find.text('Zajęcia dziecka')).dy,
          lessThan(tester.getTopLeft(find.text('Zajęcia rodzica')).dy),
        );
        await tester.tap(find.byType(PopupMenuButton<String>));
        await tester.pumpAndSettle();
        await tester.tap(
          find.descendant(
            of: find.byType(PopupMenuItem<String>),
            matching: find.text('Jan Klient'),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('Zajęcia rodzica'), findsNothing);
        expect(find.text('Zajęcia dziecka'), findsOneWidget);
        expect(find.text('Frekwencja: 0%'), findsOneWidget);
        expect(api.queries.last.scope.profileId, 'child');
        await tester.tap(find.text('Wejścia'));
        await tester.pumpAndSettle();
        expect(find.text('Wszystkie karnety'), findsOneWidget);
        expect(find.text('Historia'), findsOneWidget);
        expect(usageQueries.last.profileId, 'child');
        expect(find.text('Jan Klient'), findsOneWidget);
        await tester.tap(find.text('Wyczyść filtry'));
        await tester.pumpAndSettle();
        expect(usageQueries.last.profileId, isNull);
        expect(find.text('Wszyscy uczestnicy'), findsOneWidget);
        await tester.tap(find.text('Zajęcia'));
        await tester.pumpAndSettle();
        expect(find.text('Frekwencja: 50%'), findsOneWidget);
      },
    );

    testWidgets('a late attendance response cannot cross accounts', (
      tester,
    ) async {
      final late = Completer<List<TrainingHistoryEntry>>();
      final api = _Api()
        ..load = (query) async =>
            query.scope.accountId == 'parent' ? await late.future : [];
      final container = await pump(tester, api, settle: false);
      await tester.pump();
      await tester.tap(find.byType(PopupMenuButton<String>));
      await tester.pump(const Duration(milliseconds: 300));
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        const Session(
          accountId: 'other-account',
          displayName: 'Nowe konto',
          roles: [AppRole.client],
          activeRole: AppRole.client,
          staffProfileId: null,
          profiles: [
            Profile(id: 'other', displayName: 'Nowa osoba', isChild: false),
          ],
          activeProfileId: 'other',
        ),
      );
      await tester.pumpAndSettle();
      late.complete([
        TrainingHistoryEntry.fromJson(_entryJson(title: 'Stare dane')),
      ]);
      await tester.pumpAndSettle();
      expect(find.text('Stare dane'), findsNothing);
      expect(find.text('Anna Klient'), findsNothing);
      expect(find.text('Jan Klient'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('tabs and participant filters fit a narrow enlarged view', (
      tester,
    ) async {
      final api = _Api()..items = [_entryJson()];
      await pump(tester, api, width: 320, scale: 1.6);
      expect(find.text('Wszyscy uczestnicy'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Wejścia'));
      await tester.pumpAndSettle();
      expect(find.text('Wszystkie karnety'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
