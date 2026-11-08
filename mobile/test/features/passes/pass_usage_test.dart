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
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/features/passes/presentation/pass_usage_screen.dart';
import 'package:mobile/features/accounts/presentation/profile_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session _session({
  String account = 'account',
  String profile = 'self',
  AppRole role = AppRole.client,
}) => Session(
  accountId: account,
  displayName: 'Anna',
  roles: const [AppRole.client, AppRole.trainer],
  activeRole: role,
  staffProfileId: 'staff',
  profiles: const [
    Profile(id: 'self', displayName: 'Anna', isChild: false),
    Profile(id: 'child', displayName: 'Jan', isChild: true),
  ],
  activeProfileId: profile,
);

class _Auth extends AuthController {
  @override
  AuthState build() => Authenticated(_session());
  void replace(Session session, {bool choosingRole = false}) =>
      state = Authenticated(session, choosingRole: choosingRole);
}

PassUsageEntry _entry(
  String kind, {
  String? title,
  int? change = -1,
  bool cancelled = false,
  String profile = 'self',
}) => PassUsageEntry(
  id: kind,
  reservationId: kind == 'CORRECTION' ? null : 'reservation-$kind',
  kind: kind,
  entriesChange: change,
  occurredAt: DateTime(2026, 11, 6, 12),
  title: title ?? 'Pole dance $kind',
  startsAt: kind == 'CORRECTION' ? null : DateTime(2026, 11, 5, 18),
  endsAt: kind == 'CORRECTION' ? null : DateTime(2026, 11, 5, 19),
  cancelled: cancelled,
  profileId: profile,
  profileName: profile == 'self' ? 'Anna' : 'Jan',
  passId: 'pass',
  passName: 'Karnet Pole Dance',
);

PassUsagePage _page({
  int page = 0,
  int total = 4,
  int pages = 1,
  List<PassUsageEntry>? items,
}) => PassUsagePage(
  items:
      items ??
      [
        _entry('RESERVED'),
        _entry('CONSUMED'),
        _entry('RETURNED', change: 0, cancelled: true),
        _entry('CORRECTION', change: 2),
      ],
  page: page,
  totalItems: total,
  totalPages: pages,
  participants: const [
    PassUsageOption(id: 'self', name: 'Anna'),
    PassUsageOption(id: 'child', name: 'Jan'),
  ],
  products: const [
    PassUsageOption(id: 'pole', name: 'Pole Dance'),
    PassUsageOption(id: 'kids', name: 'Dziecięcy'),
  ],
);

class _Api implements PassUsageApi {
  final calls = <PassUsageQuery>[];
  bool fails = false;
  Future<PassUsagePage> Function(PassUsageQuery)? load;
  @override
  Future<PassUsagePage> fetch(PassUsageQuery query) async {
    calls.add(query);
    if (fails) {
      throw const ApiException(statusCode: 503, message: 'Unavailable');
    }
    return load?.call(query) ?? _page();
  }
}

class _Adapter implements HttpClientAdapter {
  RequestOptions? request;
  int status = 200;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    request = options;
    return ResponseBody.fromString(
      jsonEncode({
        'participants': [
          {'id': 'child', 'name': 'Jan'},
        ],
        'products': [
          {'id': 'pt', 'name': 'Pakiet PT'},
        ],
        'selectedPassName': null,
        'entries': {
          'items': [
            {
              'id': 'ledger',
              'reservationId': 'reservation',
              'kind': 'CONSUMED',
              'entriesChange': null,
              'occurredAt': '2026-11-06T10:00:00Z',
              'title': null,
              'serviceTypeName': 'Trening personalny',
              'startsAt': '2026-11-05T17:00:00Z',
              'endsAt': '2026-11-05T18:00:00Z',
              'cancelled': false,
              'profileId': 'child',
              'profileName': 'Jan',
              'passId': 'pass',
              'passName': 'Pakiet PT',
            },
          ],
          'page': 2,
          'totalItems': 45,
          'totalPages': 3,
        },
      }),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Api api, {
  bool settle = true,
  double width = 600,
  double scale = 1,
  bool profileHome = false,
}) async {
  tester.view.physicalSize = Size(width, 1300);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      authControllerProvider.overrideWith(_Auth.new),
      passUsageApiProvider.overrideWithValue(api),
    ],
  );
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox());
    container.dispose();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('pl'),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: profileHome
            ? const ProfileScreen()
            : const PassUsageScreen(accountId: 'account'),
      ),
    ),
  );
  if (settle) await tester.pumpAndSettle();
  return container;
}

void main() {
  testWidgets(
    'hides participant options in an open filter after an account change',
    (tester) async {
      final container = await _pump(tester, _Api());
      await tester.tap(find.text('Wszyscy uczestnicy'));
      await tester.pumpAndSettle();
      expect(find.text('Jan'), findsOneWidget);
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _session(account: 'new-account'),
      );
      await tester.pumpAndSettle();
      expect(find.text('Jan'), findsNothing);
      expect(find.text('Karnet Pole Dance'), findsNothing);
    },
  );

  testWidgets('profile does not duplicate the schedule history', (
    tester,
  ) async {
    final api = _Api();
    await _pump(tester, api, profileHome: true);
    expect(api.calls, isEmpty);
    expect(find.text('Historia wejść'), findsNothing);
  });

  testWidgets(
    'combines participant, product and inclusive day filters and clears them together',
    (tester) async {
      final api = _Api()
        ..load = (query) async =>
            query.profileId != null && query.productId != null
            ? _page(total: 0, pages: 0, items: [])
            : _page();
      await _pump(tester, api);
      await tester.tap(find.text('Wszyscy uczestnicy'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Jan').last);
      await tester.pumpAndSettle();
      expect(api.calls.last.profileId, 'child');
      await tester.tap(find.text('Wszystkie karnety'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Pole Dance'));
      await tester.pumpAndSettle();
      expect(api.calls.last.productId, 'pole');
      expect(find.text('Brak wpisów dla wybranych filtrów.'), findsOneWidget);
      await tester.tap(find.text('Okres'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ostatnie 30 dni'));
      await tester.pumpAndSettle();
      final now = DateTime.now();
      expect(
        DateTime.parse(api.calls.last.from!).toLocal(),
        DateTime(now.year, now.month, now.day - 29),
      );
      expect(
        DateTime.parse(api.calls.last.to!).toLocal(),
        DateTime(now.year, now.month, now.day + 1),
      );
      await tester.tap(find.text('Wyczyść filtry'));
      await tester.pumpAndSettle();
      expect(api.calls.last.profileId, isNull);
      expect(api.calls.last.productId, isNull);
      expect(api.calls.last.from, isNull);
      expect(api.calls.last.to, isNull);
      expect(api.calls.last.page, 0);
      expect(find.text('Zarezerwowane'), findsOneWidget);
    },
  );

  testWidgets('changing a filter from a later page starts at page zero', (
    tester,
  ) async {
    final api = _Api()
      ..load = (query) async => _page(
        page: query.page,
        total: 40,
        pages: 2,
        items: [_entry('CONSUMED')],
      );
    await _pump(tester, api);
    await tester.tap(find.text('Następne'));
    await tester.pumpAndSettle();
    expect(api.calls.last.page, 1);
    await tester.tap(find.text('Wszyscy uczestnicy'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Jan').last);
    await tester.pumpAndSettle();
    expect(api.calls.last.page, 0);
    expect(api.calls.last.profileId, 'child');
  });

  testWidgets(
    'uses a table on a wide screen and separates accounting dates from class dates',
    (tester) async {
      await _pump(tester, _Api(), width: 1000, scale: 1.6);
      expect(find.byType(Table), findsOneWidget);
      expect(find.text('Data wpisu'), findsOneWidget);
      expect(find.textContaining('Termin: 5 lis 2026'), findsNWidgets(3));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'account history remains available after changing the active participant on the same account',
    (tester) async {
      final container = await _pump(tester, _Api());
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _session(profile: 'child'),
      );
      await tester.pumpAndSettle();
      expect(find.text('Wszyscy uczestnicy'), findsOneWidget);
      expect(find.text('Pole dance CONSUMED'), findsOneWidget);
      expect(
        find.text('Zmieniono konto. Otwórz ponownie historię wejść.'),
        findsNothing,
      );
    },
  );

  test(
    'GET sends account filters and pagination and preserves unlimited usage',
    () async {
      final adapter = _Adapter();
      final api = PassUsageApi(Dio()..httpClientAdapter = adapter);
      const query = (
        accountId: 'account',
        profileId: 'child',
        passId: 'pass',
        productId: 'pt',
        from: '2026-11-01T00:00:00Z',
        to: '2026-12-01T00:00:00Z',
        page: 2,
      );
      final result = await api.fetch(query);
      expect(adapter.request!.method, 'GET');
      expect(adapter.request!.path, '/api/v1/me/pass-entry-history');
      expect(adapter.request!.queryParameters, {
        'page': 2,
        'size': 20,
        'profileId': 'child',
        'passId': 'pass',
        'productId': 'pt',
        'from': query.from,
        'to': query.to,
      });
      expect(result.items.single.entriesChange, isNull);
      expect(result.items.single.title, 'Trening personalny');
      expect(
        result.items.single.startsAt,
        DateTime.parse('2026-11-05T17:00:00Z').toLocal(),
      );
      expect(result.totalItems, 45);
      adapter.status = 503;
      await expectLater(api.fetch(query), throwsA(isA<ApiException>()));
    },
  );

  testWidgets(
    'shows the three accounting states and a separate signed correction without an extra charge',
    (tester) async {
      final api = _Api();
      await _pump(tester, api);
      expect(find.text('Zarezerwowane'), findsOneWidget);
      expect(find.text('Wykorzystane'), findsOneWidget);
      expect(find.text('Zwrócone'), findsOneWidget);
      expect(find.text('Korekta salda'), findsOneWidget);
      expect(find.text('+2 wejścia'), findsOneWidget);
      expect(find.text('Odwołane'), findsOneWidget);
      expect(find.textContaining('18:00–19:00'), findsNWidgets(3));
      expect(find.textContaining('−1'), findsNothing);
      expect(api.calls.single.profileId, isNull);
      expect(api.calls.single.passId, isNull);
    },
  );

  testWidgets(
    'pages through the selected pass and refreshes on resume back to page one',
    (tester) async {
      final api = _Api()
        ..load = (query) async => _page(
          page: query.page,
          total: 40,
          pages: 2,
          items: [_entry('CONSUMED', title: 'Strona ${query.page}')],
        );
      await _pump(tester, api);
      await tester.tap(find.text('Następne'));
      await tester.pumpAndSettle();
      expect(find.text('Strona 1'), findsOneWidget);
      expect(api.calls.last.page, 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('Strona 0'), findsOneWidget);
      expect(api.calls.last.page, 0);
      expect(api.calls, hasLength(3));
    },
  );

  testWidgets('distinguishes a failed request, retry and an empty history', (
    tester,
  ) async {
    final api = _Api()..fails = true;
    await _pump(tester, api);
    expect(find.text('Nie udało się pobrać historii wejść.'), findsOneWidget);
    api.fails = false;
    api.load = (_) async => _page(total: 0, pages: 0, items: []);
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.text('Nie ma jeszcze historii wejść.'), findsOneWidget);
    expect(find.text('Następne'), findsNothing);
    expect(api.calls, hasLength(2));
  });

  testWidgets(
    'a late response cannot show the previous accounts history after switching accounts',
    (tester) async {
      final late = Completer<PassUsagePage>();
      final api = _Api()..load = (_) => late.future;
      final container = await _pump(tester, api, settle: false);
      await tester.pump();
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _session(account: 'new-account'),
      );
      await tester.pumpAndSettle();
      late.complete(_page());
      await tester.pumpAndSettle();
      expect(
        find.text('Zmieniono konto. Otwórz ponownie historię wejść.'),
        findsOneWidget,
      );
      expect(find.text('Karnet Pole Dance'), findsNothing);
      expect(find.text('Pole dance CONSUMED'), findsNothing);
      expect(api.calls, hasLength(1));
    },
  );

  for (final rolePicker in [false, true]) {
    testWidgets(
      'removes pass data after changing account or opening role picker ($rolePicker)',
      (tester) async {
        final api = _Api();
        final container = await _pump(tester, api);
        final auth = container.read(authControllerProvider.notifier) as _Auth;
        auth.replace(
          _session(account: rolePicker ? 'account' : 'new-account'),
          choosingRole: rolePicker,
        );
        await tester.pumpAndSettle();
        expect(find.text('Karnet Pole Dance'), findsNothing);
        expect(find.text('Pole dance CONSUMED'), findsNothing);
      },
    );
  }

  testWidgets(
    'keeps long session names, negative corrections and paging readable on a narrow screen',
    (tester) async {
      final api = _Api()
        ..load = (_) async => _page(
          total: 40,
          pages: 2,
          items: [
            _entry(
              'CONSUMED',
              title: 'Długi trening personalny z ćwiczeniami technicznymi',
              change: null,
            ),
            _entry('CORRECTION', change: -2),
            _entry('REVIEW', change: null),
          ],
        );
      await _pump(tester, api, width: 320, scale: 1.6);
      expect(find.text('−2 wejścia'), findsOneWidget);
      expect(find.text('Do sprawdzenia ze studiem'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Następne'),
        150,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.pumpAndSettle();
      expect(find.text('Następne').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
