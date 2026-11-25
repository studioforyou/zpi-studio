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
import 'package:mobile/features/passes/data/makeup_booking_api.dart';
import 'package:mobile/features/passes/presentation/makeup_booking_screen.dart';
import 'package:mobile/features/passes/presentation/makeup_credits.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

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
        makeupBookingProvider.overrideWith(
          (ref, query) async =>
              throw const ApiException(statusCode: 500, message: 'Failed'),
        ),
      ],
    );
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
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
          home: Scaffold(
            body: Consumer(
              builder: (context, ref, _) {
                final scope = makeupBookingScope(
                  ref.watch(authControllerProvider),
                );
                return scope == null
                    ? const Text('no profile')
                    : MakeupStrip(key: ValueKey(scope), scope: scope);
              },
            ),
          ),
        ),
      ),
    );
    if (settle) await tester.pumpAndSettle();
    return container;
  }

  Future<void> openSheet(WidgetTester tester) async {
    await tester.tap(find.text('Dowiedz się więcej'));
    await tester.pumpAndSettle();
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

  testWidgets('the strip shows the full count and the sheet each make-up', (
    tester,
  ) async {
    await pump(tester, _Api((_, _) async => _page(total: 21, pages: 2)));
    expect(find.text('Masz 21 zajęć do odrobienia'), findsOneWidget);
    await openSheet(tester);
    expect(find.text('Do odrobienia'), findsOneWidget);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('wykorzystaj do 17.11'), findsOneWidget);
    expect(find.text('Wybierz nowe zajęcia'), findsOneWidget);
    expect(find.textContaining('Jedna odróbka'), findsOneWidget);
  });

  testWidgets('no strip without make-ups or while they fail to load', (
    tester,
  ) async {
    var fails = true;
    final api = _Api((_, _) async {
      if (fails) throw const ApiException(statusCode: 500, message: 'Failed');
      return _page(total: 0);
    });
    final container = await pump(tester, api);
    expect(find.byType(InfoStrip), findsNothing);
    fails = false;
    container.invalidate(makeupCreditsProvider);
    await tester.pumpAndSettle();
    expect(find.byType(InfoStrip), findsNothing);
    expect(api.calls.length, 2);
  });

  testWidgets('shows more make-ups page by page and retries a failed page', (
    tester,
  ) async {
    var fails = true;
    final api = _Api((_, page) async {
      if (page == 1 && fails) {
        throw const ApiException(statusCode: 500, message: 'Failed');
      }
      return _page(
        name: page == 0 ? 'First page' : 'Second page',
        page: page,
        total: 21,
        pages: 2,
      );
    });
    await pump(tester, api);
    await openSheet(tester);
    expect(find.text('First page'), findsOneWidget);
    await tester.tap(find.text('Pokaż więcej'));
    await tester.pumpAndSettle();
    expect(
      find.text('Nie udało się pobrać odróbek. Spróbuj ponownie.'),
      findsOneWidget,
    );
    fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.text('First page'), findsOneWidget);
    expect(find.text('Second page'), findsOneWidget);
    expect(find.text('Pokaż więcej'), findsNothing);
    expect(api.calls, contains(('self', 1)));
  });

  testWidgets('choosing new classes opens the make-up mode for the credit', (
    tester,
  ) async {
    await pump(tester, _Api((_, _) async => _page()));
    await openSheet(tester);
    await tester.tap(find.text('Wybierz nowe zajęcia'));
    await tester.pumpAndSettle();
    final screen = tester.widget<MakeupBookingScreen>(
      find.byType(MakeupBookingScreen),
    );
    expect(screen.creditId, 'Pole dance-0');
    expect(screen.scope, (accountId: 'account', profileId: 'self'));
    expect(find.text('Do odrobienia'), findsNothing);
  });

  testWidgets('ignores a late response from the previous participant', (
    tester,
  ) async {
    final late = Completer<MakeupCreditPage>();
    final api = _Api(
      (profile, _) => profile == 'self'
          ? late.future
          : Future.value(_page(name: 'Child credit', total: 2)),
    );
    final container = await pump(tester, api, settle: false);
    (container.read(authControllerProvider.notifier) as _Auth).replace(
      _session(profile: 'child'),
    );
    await tester.pumpAndSettle();
    late.complete(_page(name: 'Parent credit', total: 5));
    await tester.pumpAndSettle();
    expect(find.text('Masz 2 zajęcia do odrobienia'), findsOneWidget);
    await openSheet(tester);
    expect(find.text('Child credit'), findsOneWidget);
    expect(find.text('Parent credit'), findsNothing);
  });

  testWidgets('refreshes when a credit expires while the view stays open', (
    tester,
  ) async {
    var result = _page(expiry: DateTime.now().add(const Duration(seconds: 2)));
    final api = _Api((_, _) async => result);
    await pump(tester, api);
    expect(find.byType(InfoStrip), findsOneWidget);
    result = _page(total: 0);
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    expect(find.byType(InfoStrip), findsNothing);
  });

  testWidgets('does not request credits without a participant profile', (
    tester,
  ) async {
    final api = _Api((_, _) async => _page());
    await pump(tester, api, session: _session(profile: null));
    expect(api.calls, isEmpty);
    expect(find.text('no profile'), findsOneWidget);
  });
}
