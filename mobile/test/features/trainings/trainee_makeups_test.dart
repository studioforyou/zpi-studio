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
import 'package:mobile/features/trainings/data/trainee_makeups_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_makeups_section.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

final _today = DateTime(2026, 11, 17);
const TraineeOverviewQuery _query = (
  scope: (accountId: 'account', staffId: 'staff'),
  customerId: 'customer',
);

class _Auth extends AuthController {
  @override
  AuthState build() => Authenticated(
    Session(
      accountId: 'account',
      displayName: 'Anna Kowalska',
      roles: const [AppRole.trainer],
      activeRole: AppRole.trainer,
      staffProfileId: 'staff',
      profiles: const [],
      activeProfileId: null,
    ),
  );
}

MakeupCredit _credit({
  String id = 'credit',
  required DateTime expiresAt,
  MakeupOrigin? origin,
  List<MakeupService> services = const [
    MakeupService(id: 'calisthenics', name: 'Calisthenics'),
    MakeupService(id: 'stretching', name: 'Warsztaty stretchingu'),
  ],
}) => MakeupCredit(
  id: id,
  sourcePassId: 'pass',
  productName: 'Karnet Calisthenics - 8 wejść',
  policyName: 'Odrabianie 3 tygodnie',
  grantedAt: DateTime(2026, 11, 1, 20),
  expiresAt: expiresAt,
  origin: origin,
  services: services,
);

final _inTime = MakeupOrigin(
  title: 'Calisthenics',
  startsAt: DateTime(2026, 11, 1, 18),
  reason: MakeupReason.cancelledInTime,
);

TraineeMakeups _makeups(
  List<MakeupCredit> items, {
  List<MakeupLimit> limits = const [],
}) => TraineeMakeups(
  checkedOn: _today,
  totalCount: items.length,
  nearestExpiresAt: items.firstOrNull?.expiresAt,
  items: items,
  limits: limits,
);

class _Api implements TraineeMakeupsApi {
  _Api(this.result);
  TraineeMakeups result;
  bool fails = false;
  int calls = 0;

  @override
  Future<TraineeMakeups> fetch(String customerId) async {
    calls++;
    if (fails) {
      throw const ApiException(statusCode: 503, message: 'Unavailable');
    }
    return result;
  }
}

class _Adapter implements HttpClientAdapter {
  Map<String, dynamic> body = {};
  final calls = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls.add(options);
    return ResponseBody.fromString(
      jsonEncode(body),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Future<void> _pump(WidgetTester tester, _Api api) async {
  tester.view.physicalSize = const Size(600, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    ProviderScope(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        traineeMakeupsApiProvider.overrideWithValue(api),
      ],
      child: MaterialApp(
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: const [TraineeMakeupsSection(query: _query)],
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  test('reads the trainee credits with a single GET', () async {
    final adapter = _Adapter()
      ..body = {
        'checkedOn': '2026-11-17',
        'checkedAt': '2026-11-17T12:00:00Z',
        'customerId': 'customer',
        'totalCount': 1,
        'nearestExpiresAt': '2026-11-22T22:59:00Z',
        'items': [
          {
            'id': 'credit',
            'sourcePassId': 'pass',
            'productName': 'Karnet Calisthenics - 8 wejść',
            'policyName': null,
            'grantedAt': '2026-11-01T22:59:00Z',
            'expiresAt': '2026-11-22T22:59:00Z',
            'origin': {
              'title': 'Calisthenics',
              'startsAt': '2026-11-01T17:00:00Z',
              'reason': 'CANCELLED_BY_STUDIO',
            },
            'services': [
              {'id': 'calisthenics', 'name': 'Calisthenics'},
            ],
          },
        ],
        'limits': [
          {
            'passId': 'pass',
            'productName': 'Karnet Calisthenics - 8 wejść',
            'granted': 3,
            'max': 3,
            'reached': true,
          },
        ],
      };
    final api = TraineeMakeupsApi(Dio()..httpClientAdapter = adapter);

    final result = await api.fetch('customer');

    expect(adapter.calls.single.method, 'GET');
    expect(
      adapter.calls.single.path,
      '/api/v1/me/trainees/customer/makeup-credits',
    );
    expect(result.totalCount, 1);
    expect(result.items.single.origin!.reason, MakeupReason.cancelledByStudio);
    expect(result.items.single.policyName, isNull);
    expect(result.items.single.expiresAt.isUtc, isFalse);
    expect(result.limitFor('pass')!.reached, isTrue);
  });

  testWidgets(
    'summarises the credits and highlights the ones expiring within 14 days',
    (tester) async {
      await _pump(
        tester,
        _Api(
          _makeups([
            _credit(
              id: 'soon',
              expiresAt: DateTime(2026, 11, 22, 23, 59),
              origin: _inTime,
            ),
            _credit(id: 'later', expiresAt: DateTime(2026, 12, 4, 10)),
          ]),
        ),
      );

      expect(find.text('Odróbki'), findsOneWidget);
      expect(find.text('2 odróbki'), findsOneWidget);
      expect(find.text('Najbliższa wygasa 22.11.2026'), findsOneWidget);
      expect(find.text('Do 22.11.2026'), findsOneWidget);
      expect(find.text('Do 04.12.2026'), findsOneWidget);
      expect(
        find.text('Za: Calisthenics, 01.11.2026 · odwołane w terminie'),
        findsOneWidget,
      );
      expect(find.text('Przyznana przez studio'), findsOneWidget);
      expect(find.text('Wygasa za 5 dni'), findsOneWidget);
      // 17 days left: no warning.
      expect(find.textContaining('Wygasa za 17'), findsNothing);
      expect(find.byType(TraineeMakeupCard), findsNWidgets(2));
    },
  );

  test('a credit granted after an upheld dispute says so in its origin', () {
    final l10n = lookupAppLocalizations(const Locale('pl'));
    final credit = _credit(
      expiresAt: DateTime(2026, 12, 4, 10),
      origin: MakeupOrigin(
        title: 'Trening personalny',
        startsAt: DateTime(2026, 11, 10, 18),
        reason: MakeupReason.disputeUpheld,
      ),
    );
    expect(
      makeupOriginLine(l10n, credit),
      'Za: Trening personalny, 10.11.2026 · uznane zgłoszenie niezgodności',
    );
  });

  testWidgets('opens the details with the services the credit can book', (
    tester,
  ) async {
    await _pump(
      tester,
      _Api(
        _makeups(
          [_credit(expiresAt: DateTime(2026, 11, 22, 23, 59), origin: _inTime)],
          limits: const [
            MakeupLimit(
              passId: 'pass',
              productName: 'Karnet Calisthenics - 8 wejść',
              granted: 2,
              max: 3,
              reached: false,
            ),
          ],
        ),
      ),
    );

    await tester.tap(find.byType(TraineeMakeupCard));
    await tester.pumpAndSettle();

    final sheet = find.byType(BottomSheet);
    expect(sheet, findsOneWidget);
    Finder inSheet(Finder finder) =>
        find.descendant(of: sheet, matching: finder);
    expect(inSheet(find.text('22.11.2026, 23:59')), findsOneWidget);
    expect(
      inSheet(find.text('Calisthenics, 01.11.2026 18:00')),
      findsOneWidget,
    );
    expect(inSheet(find.text('odwołane w terminie')), findsOneWidget);
    expect(inSheet(find.text('Odrabianie 3 tygodnie')), findsOneWidget);
    expect(
      inSheet(find.text('Z tego karnetu przyznano 2 z 3 odróbek.')),
      findsOneWidget,
    );
    expect(inSheet(find.widgetWithText(Chip, 'Calisthenics')), findsOneWidget);
    expect(
      inSheet(find.widgetWithText(Chip, 'Warsztaty stretchingu')),
      findsOneWidget,
    );
    // Read-only: nothing to book or spend.
    expect(inSheet(find.byType(FilledButton)), findsNothing);
    expect(inSheet(find.byType(ElevatedButton)), findsNothing);
  });

  testWidgets('says when the trainee has no credits', (tester) async {
    await _pump(tester, _Api(_makeups(const [])));

    expect(find.text('Brak odróbek'), findsOneWidget);
    expect(find.textContaining('Brak dostępnych odróbek.'), findsOneWidget);
    expect(find.byType(TraineeMakeupCard), findsNothing);
  });

  testWidgets('shows when a pass has used up its make-up limit', (
    tester,
  ) async {
    await _pump(
      tester,
      _Api(
        _makeups(
          [_credit(expiresAt: DateTime(2026, 12, 7, 23, 59))],
          limits: const [
            MakeupLimit(
              passId: 'pass',
              productName: 'Poprzedni pakiet PT — 5 treningów',
              granted: 2,
              max: 2,
              reached: true,
            ),
          ],
        ),
      ),
    );

    expect(
      find.text(
        'Limit odróbek wykorzystany: Poprzedni pakiet PT — 5 treningów (2 z 2)',
      ),
      findsOneWidget,
    );
  });

  testWidgets('marks a credit that expires today', (tester) async {
    await _pump(
      tester,
      _Api(_makeups([_credit(expiresAt: DateTime(2026, 11, 17, 23, 59))])),
    );

    expect(find.text('Najbliższa wygasa dziś'), findsOneWidget);
    expect(find.text('Wygasa dziś'), findsOneWidget);
  });

  testWidgets('offers a retry when the credits cannot be read', (tester) async {
    final api = _Api(
      _makeups([_credit(expiresAt: DateTime(2026, 12, 7, 23, 59))]),
    )..fails = true;
    await _pump(tester, api);

    expect(find.text('Nie udało się wczytać odróbek.'), findsOneWidget);
    api.fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();

    expect(find.text('Nie udało się wczytać odróbek.'), findsNothing);
    expect(find.text('1 odróbka'), findsOneWidget);
    expect(api.calls, 2);
  });
}
