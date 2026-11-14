import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/substitution_api.dart';
import 'package:mobile/features/schedule/presentation/substitution_offers_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const session = Session(
  accountId: 'account',
  displayName: 'Marek',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'marek',
  profiles: [],
  activeProfileId: null,
);

class Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(session);
}

SubstitutionOffer offer({
  String id = 'offer',
  String status = 'OPEN',
  bool available = true,
}) => SubstitutionOffer(
  id: id,
  eventId: 'event',
  version: status == 'OPEN' ? 0 : 1,
  status: status,
  kind: 'PERSONAL_TRAINING',
  title: id == 'busy' ? 'Zajęty termin' : 'Trening personalny',
  rooms: 'Sala 1',
  startsAt: DateTime.now().add(const Duration(days: 1)),
  endsAt: DateTime.now().add(const Duration(days: 1, hours: 1)),
  originalTrainer: 'Ewa Wiśniewska',
  approvalRequired: true,
  canClaim: status == 'OPEN' && available,
  canWithdraw: status == 'CLAIMED',
  unavailableReason: available ? null : 'UNAVAILABLE',
);

class Api extends SubstitutionApi {
  Api() : super(Dio());
  SubstitutionOffer current = offer();
  Object? failure;
  int actions = 0;
  bool? withdrawn;
  @override
  Future<List<SubstitutionOffer>> fetchOffers(OfferQuery query) async =>
      query.mine
      ? current.status == 'OPEN'
            ? []
            : [current]
      : [
          if (current.status == 'OPEN') current,
          offer(id: 'busy', available: false),
        ];
  @override
  Future<SubstitutionOffer> fetchOffer(String id) async =>
      id == 'busy' ? offer(id: 'busy', available: false) : current;
  @override
  Future<SubstitutionOffer> actOnOffer(
    SubstitutionOffer target, {
    required bool withdraw,
  }) async {
    actions++;
    withdrawn = withdraw;
    if (failure case final error?) throw error;
    current = offer(status: withdraw ? 'CANCELLED' : 'CLAIMED');
    return current;
  }
}

Future<void> pump(
  WidgetTester tester,
  Api api, {
  double scale = 1,
  Size size = const Size(430, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    ProviderScope(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(Auth.new),
        substitutionApiProvider.overrideWithValue(api),
      ],
      child: MaterialApp(
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: const SubstitutionOffersScreen(),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'matching filter hides conflicts and reveals their explanation on demand',
    (tester) async {
      final api = Api();
      await pump(tester, api);
      expect(find.text('Trening personalny'), findsOneWidget);
      expect(find.text('Zajęty termin'), findsNothing);
      await tester.tap(find.text('Pasujące do mnie'));
      await tester.pumpAndSettle();
      expect(find.text('Zajęty termin'), findsOneWidget);
      expect(find.text('Kolizja z grafikiem lub urlopem'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('claim requires confirmation and shows the pending decision', (
    tester,
  ) async {
    final api = Api();
    await pump(tester, api);
    await tester.tap(find.text('Trening personalny'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Zgłoś przejęcie'));
    await tester.pumpAndSettle();
    expect(api.actions, 0);
    await tester.tap(find.widgetWithText(FilledButton, 'Potwierdź'));
    await tester.pumpAndSettle();
    expect(api.actions, 1);
    expect(api.withdrawn, false);
    expect(find.text('Czeka na studio'), findsOneWidget);
    expect(find.text('Wycofaj zgłoszenie'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('candidate can withdraw a pending claim with confirmation', (
    tester,
  ) async {
    final api = Api()..current = offer(status: 'CLAIMED');
    await pump(tester, api);
    await tester.tap(find.text('Moje zgłoszenia'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Trening personalny'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Wycofaj zgłoszenie'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Wycofaj'));
    await tester.pumpAndSettle();
    expect(api.actions, 1);
    expect(api.withdrawn, true);
    // Both the list card and the open details reflect the withdrawal.
    expect(find.text('Wycofane lub odwołane'), findsNWidgets(2));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a conflict does not display a successful claim', (tester) async {
    final api = Api()
      ..failure = const SubstitutionException(
        code: 'SUBSTITUTION_NOT_ELIGIBLE',
        message: 'conflict',
      );
    await pump(tester, api);
    await tester.tap(find.text('Trening personalny'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Zgłoś przejęcie'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Potwierdź'));
    await tester.pumpAndSettle();
    expect(
      find.text(
        'Ten termin nie jest już dla Ciebie dostępny. Sprawdź grafik lub urlopy.',
      ),
      findsOneWidget,
    );
    expect(api.current.status, 'OPEN');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'returning to the app refreshes a studio decision in the open details',
    (tester) async {
      final api = Api()..current = offer(status: 'CLAIMED');
      await pump(tester, api);
      await tester.tap(find.text('Moje zgłoszenia'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Trening personalny'));
      await tester.pumpAndSettle();
      expect(find.text('Wycofaj zgłoszenie'), findsOneWidget);
      api.current = offer(status: 'APPROVED');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('Wycofaj zgłoszenie'), findsNothing);
      expect(find.text('Przyjęte'), findsNWidgets(2));
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'compact list remains usable with larger text on a narrow phone',
    (tester) async {
      final api = Api();
      await pump(tester, api, scale: 1.5, size: const Size(360, 900));
      expect(tester.takeException(), isNull);
      expect(find.text('Trening personalny'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
