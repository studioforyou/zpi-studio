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
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/passes/presentation/makeup_credits_tab.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session _session({String account = 'account', String? profile = 'self'}) =>
    Session(
      accountId: account,
      displayName: 'Parent',
      roles: const [AppRole.client],
      activeRole: AppRole.client,
      staffProfileId: null,
      profiles: const [
        Profile(id: 'self', displayName: 'Anna', isChild: false),
        Profile(id: 'child', displayName: 'Jan', isChild: true),
      ],
      activeProfileId: profile,
    );

class _Auth extends AuthController {
  _Auth(this.initial);
  final Session initial;
  @override
  AuthState build() => Authenticated(initial);
  void replace(Session session) => state = Authenticated(session);
}

class _Api implements MakeupCreditsApi {
  _Api(this.load);
  final Future<MakeupCreditPage> Function(String, int) load;
  final calls = <(String, int)>[];
  @override
  Future<MakeupCreditPage> fetch(String profileId, {int page = 0}) {
    calls.add((profileId, page));
    return load(profileId, page);
  }
}

MakeupCreditPage _page({
  String name = 'Pole dance',
  int page = 0,
  int total = 1,
  int pages = 1,
  DateTime? expiry,
}) => MakeupCreditPage(
  items: total == 0
      ? []
      : [
          MakeupCredit(
            id: '$name-$page',
            productName: name,
            expiresAt: expiry ?? DateTime(2035, 11, 17, 18, 43),
          ),
        ],
  page: page,
  totalItems: total,
  totalPages: total == 0 ? 0 : pages,
);

class _Adapter implements HttpClientAdapter {
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
      jsonEncode({
        'items': [
          {
            'id': 'credit',
            'productName': 'Pole dance',
            'expiresAt': '2035-11-17T17:43:00Z',
          },
        ],
        'page': 1,
        'totalItems': 21,
        'totalPages': 2,
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

void main() {
  Future<ProviderContainer> pump(
    WidgetTester tester,
    _Api api, {
    Session? session,
    bool settle = true,
  }) async {
    final container = ProviderContainer(
      overrides: [
        authControllerProvider.overrideWith(() => _Auth(session ?? _session())),
        makeupCreditsApiProvider.overrideWithValue(api),
      ],
    );
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    });
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: Scaffold(body: MakeupCreditsTab()),
        ),
      ),
    );
    if (settle) await tester.pumpAndSettle();
    return container;
  }

  test(
    'API scopes the request, sends pagination and converts UTC expiry',
    () async {
      final adapter = _Adapter();
      final dio = Dio()..httpClientAdapter = adapter;
      addTearDown(() => dio.close(force: true));
      final api = MakeupCreditsApi(dio);
      final result = await api.fetch('child', page: 1);
      expect(adapter.request!.path, '/api/v1/me/profiles/child/makeup-credits');
      expect(adapter.request!.queryParameters, {'page': 1, 'size': 20});
      expect(result.totalItems, 21);
      expect(
        result.items.single.expiresAt,
        DateTime.utc(2035, 11, 17, 17, 43).toLocal(),
      );
      adapter.status = 403;
      await expectLater(api.fetch('child'), throwsA(isA<ApiException>()));
    },
  );

  testWidgets('shows the full count, participant and exact local expiry', (
    tester,
  ) async {
    await pump(tester, _Api((_, _) async => _page(total: 21, pages: 2)));
    expect(find.text('Anna'), findsOneWidget);
    expect(find.text('21'), findsOneWidget);
    expect(find.text('zajęć do odrobienia'), findsOneWidget);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Do 17.11.2035, 18:43'), findsOneWidget);
    expect(find.textContaining('Możliwość wykorzystania'), findsNothing);
  });

  testWidgets('keeps the explanation behind an accessible information button', (
    tester,
  ) async {
    await pump(tester, _Api((_, _) async => _page()));
    expect(find.textContaining('Jedna odróbka'), findsNothing);
    await tester.tap(find.byTooltip('Jak działa odrabianie?'));
    await tester.pumpAndSettle();
    expect(find.text('Jak działa odrabianie?'), findsOneWidget);
    expect(find.textContaining('Jedna odróbka'), findsOneWidget);
    expect(find.textContaining('Nazwa karnetu'), findsOneWidget);
    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();
    expect(find.textContaining('Jedna odróbka'), findsNothing);
    expect(find.text('Pole dance'), findsOneWidget);
  });

  testWidgets('refreshes an empty list without showing old credits', (
    tester,
  ) async {
    var result = _page(total: 0);
    final api = _Api((_, _) async => result);
    await pump(tester, api);
    expect(find.text('0'), findsOneWidget);
    expect(find.byIcon(Icons.event_repeat_outlined), findsNothing);
    result = _page();
    await tester.drag(find.byType(ListView), const Offset(0, 350));
    await tester.pumpAndSettle();
    expect(find.text('1'), findsOneWidget);
    expect(find.text('zajęcie do odrobienia'), findsOneWidget);
    expect(api.calls.length, greaterThan(1));
  });

  testWidgets('retries a failed request', (tester) async {
    var fails = true;
    await pump(
      tester,
      _Api((_, _) async {
        if (fails) throw const ApiException(statusCode: 500, message: 'Failed');
        return _page();
      }),
    );
    expect(
      find.text('Nie udało się pobrać odróbek. Spróbuj ponownie.'),
      findsOneWidget,
    );
    fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.text('Pole dance'), findsOneWidget);
  });

  testWidgets('pages forward and back while keeping the full count', (
    tester,
  ) async {
    final api = _Api(
      (_, page) async => _page(
        name: page == 0 ? 'First page' : 'Second page',
        page: page,
        total: 21,
        pages: 2,
      ),
    );
    await pump(tester, api);
    await tester.tap(find.text('Następne'));
    await tester.pumpAndSettle();
    expect(find.text('Second page'), findsOneWidget);
    expect(find.text('2 z 2'), findsOneWidget);
    expect(find.text('21'), findsOneWidget);
    await tester.tap(find.text('Poprzednie'));
    await tester.pumpAndSettle();
    expect(find.text('First page'), findsOneWidget);
    expect(api.calls, contains(('self', 1)));
  });

  testWidgets('ignores a late response from the previous participant', (
    tester,
  ) async {
    final late = Completer<MakeupCreditPage>();
    final api = _Api(
      (profile, _) => profile == 'self'
          ? late.future
          : Future.value(_page(name: 'Child credit')),
    );
    final container = await pump(tester, api, settle: false);
    (container.read(authControllerProvider.notifier) as _Auth).replace(
      _session(profile: 'child'),
    );
    await tester.pumpAndSettle();
    late.complete(_page(name: 'Parent credit'));
    await tester.pumpAndSettle();
    expect(find.text('Child credit'), findsOneWidget);
    expect(find.text('Parent credit'), findsNothing);
    expect(find.text('Jan'), findsOneWidget);
  });

  testWidgets('resets pagination and cache when the login changes', (
    tester,
  ) async {
    var name = 'First login';
    final api = _Api(
      (_, page) async => _page(name: name, page: page, total: 21, pages: 2),
    );
    final container = await pump(tester, api);
    await tester.tap(find.text('Następne'));
    await tester.pumpAndSettle();
    name = 'Second login';
    (container.read(authControllerProvider.notifier) as _Auth).replace(
      _session(account: 'second'),
    );
    await tester.pumpAndSettle();
    expect(find.text('Second login'), findsOneWidget);
    expect(find.text('First login'), findsNothing);
    expect(find.text('1 z 2'), findsOneWidget);
    expect(api.calls.last, ('self', 0));
  });

  testWidgets('refreshes when a credit expires while the view stays open', (
    tester,
  ) async {
    var result = _page(expiry: DateTime.now().add(const Duration(seconds: 2)));
    final api = _Api((_, _) async => result);
    await pump(tester, api);
    result = _page(total: 0);
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    expect(find.text('0'), findsOneWidget);
    expect(find.text('Pole dance'), findsNothing);
  });

  testWidgets('does not request credits without a participant profile', (
    tester,
  ) async {
    final api = _Api((_, _) async => _page());
    await pump(tester, api, session: _session(profile: null));
    expect(api.calls, isEmpty);
    expect(
      find.textContaining('To konto nie ma profilu klienta'),
      findsOneWidget,
    );
  });
}
