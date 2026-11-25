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
import 'package:mobile/features/passes/presentation/pass_entries.dart';
import 'package:mobile/features/accounts/presentation/profile_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

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

PassUsageQuery _query(int page, {String account = 'account'}) => (
  accountId: account,
  profileId: null,
  productId: null,
  passId: 'pass',
  from: null,
  to: null,
  includeUpcoming: true,
  page: page,
);

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Api api, {
  bool settle = true,
  double width = 390,
  double scale = 1,
  bool profileHome = false,
  bool showPass = false,
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
        theme: buildClientTheme(Brightness.light),
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
            : Scaffold(
                body: ListView(
                  children: [
                    PassEntryList(
                      queryFor: _query,
                      emptyText: 'Nie ma jeszcze historii wejść.',
                      showPass: showPass,
                      showPerson: showPass,
                    ),
                  ],
                ),
              ),
      ),
    ),
  );
  if (settle) await tester.pumpAndSettle();
  return container;
}

void main() {
  testWidgets('profile does not duplicate the schedule history', (
    tester,
  ) async {
    final api = _Api();
    await _pump(tester, api, profileHome: true);
    expect(api.calls, isEmpty);
    expect(find.text('Historia wejść'), findsNothing);
  });

  testWidgets(
    'shows what happened to each entry and the change of the balance',
    (tester) async {
      await _pump(tester, _Api());
      expect(find.text('Zarezerwowane'), findsOneWidget);
      expect(find.text('Wykorzystane'), findsOneWidget);
      expect(find.text('Zwrócone · zajęcia odwołane'), findsOneWidget);
      expect(find.text('Korekta salda'), findsOneWidget);
      expect(find.text('2 wejścia'), findsOneWidget);
      expect(find.text('czw. 5.11, 18:00'), findsNWidgets(3));
      expect(find.text('pt. 6.11'), findsOneWidget);
      expect(find.text('−1'), findsNWidgets(2));
      expect(find.text('±0'), findsOneWidget);
      expect(find.text('+2'), findsOneWidget);
      expect(find.byType(EntryTile), findsNWidgets(4));
      expect(
        [
          for (final tile in tester.widgetList<EntryTile>(
            find.byType(EntryTile),
          ))
            tile.kind,
        ],
        [
          EntryTileKind.reserved,
          EntryTileKind.used,
          EntryTileKind.returned,
          EntryTileKind.added,
        ],
      );
    },
  );

  testWidgets('a list of every pass names the pass and the person', (
    tester,
  ) async {
    final api = _Api()
      ..load = (_) async => _page(
        total: 1,
        items: [_entry('CONSUMED', change: null, profile: 'child')],
      );
    await _pump(tester, api, showPass: true);
    expect(find.text('Karnet Pole Dance · Jan'), findsOneWidget);
    expect(find.text('—'), findsOneWidget, reason: 'unlimited pass');
  });

  testWidgets('loads more entries page by page', (tester) async {
    final api = _Api()
      ..load = (query) async => _page(
        page: query.page,
        total: 3,
        pages: 3,
        items: [_entry('CONSUMED', title: 'Strona ${query.page}')],
      );
    await _pump(tester, api);
    expect(find.text('Strona 0'), findsOneWidget);
    await tester.tap(find.text('Pokaż więcej'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Pokaż więcej'));
    await tester.pumpAndSettle();
    expect(find.text('Strona 0'), findsOneWidget);
    expect(find.text('Strona 1'), findsOneWidget);
    expect(find.text('Strona 2'), findsOneWidget);
    expect(find.text('Pokaż więcej'), findsNothing);
    expect([for (final call in api.calls) call.page], [0, 1, 2]);
  });

  testWidgets('distinguishes a failed request, retry and an empty history', (
    tester,
  ) async {
    final api = _Api()..fails = true;
    await _pump(tester, api);
    expect(find.text('Nie udało się pobrać historii wejść.'), findsOneWidget);
    api
      ..fails = false
      ..load = (_) async => _page(total: 0, pages: 0, items: []);
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.text('Nie ma jeszcze historii wejść.'), findsOneWidget);
    expect(find.text('Pokaż więcej'), findsNothing);
  });

  for (final rolePicker in [false, true]) {
    testWidgets(
      'removes pass data after changing account or opening role picker ($rolePicker)',
      (tester) async {
        final container = await _pump(tester, _Api());
        expect(find.text('Wykorzystane'), findsOneWidget);
        (container.read(authControllerProvider.notifier) as _Auth).replace(
          _session(account: rolePicker ? 'account' : 'new-account'),
          choosingRole: rolePicker,
        );
        await tester.pumpAndSettle();
        expect(find.text('Wykorzystane'), findsNothing);
        expect(find.text('Pole dance CONSUMED'), findsNothing);
      },
    );
  }

  testWidgets('keeps long names and corrections readable on a narrow screen', (
    tester,
  ) async {
    final api = _Api()
      ..load = (_) async => _page(
        total: 3,
        pages: 2,
        items: [
          _entry(
            'CONSUMED',
            title: 'Długi trening personalny z ćwiczeniami technicznymi',
          ),
          _entry('CORRECTION', change: -2),
          _entry('REVIEW', change: null),
        ],
      );
    await _pump(tester, api, width: 320, scale: 1.6);
    expect(tester.takeException(), isNull);
    expect(find.text('−2'), findsOneWidget);
    expect(find.text('Do sprawdzenia ze studiem'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Pokaż więcej'), 100);
    expect(find.text('Pokaż więcej').hitTestable(), findsOneWidget);
  });

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
        includeUpcoming: true,
      );
      final result = await api.fetch(query);
      expect(adapter.request!.method, 'GET');
      expect(adapter.request!.path, '/api/v1/me/pass-entry-history');
      expect(adapter.request!.queryParameters, {
        'page': 2,
        'size': 20,
        'includeUpcoming': true,
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
}
