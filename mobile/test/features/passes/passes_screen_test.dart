import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/passes_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'parent-user',
  displayName: 'Anna Kowalska',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'self', displayName: 'Anna Kowalska', isChild: false),
    Profile(id: 'child-1', displayName: 'Jan Kowalski', isChild: true),
  ],
  activeProfileId: 'self',
);

class _SignedInAuthController extends AuthController {
  _SignedInAuthController(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

class _FakePassesApi implements PassesApi {
  _FakePassesApi({this.passes = const {}, this.offer, this.fails = false});

  final Map<String, List<OwnPass>> passes;
  final PassOffer? offer;
  bool fails;

  @override
  Future<List<OwnPass>> fetchOwnPasses(String profileId) async {
    if (fails) {
      throw const ApiException(statusCode: 500, message: 'Server error');
    }
    return passes[profileId] ?? const [];
  }

  @override
  Future<PassOffer> fetchOffer() async =>
      offer ?? const PassOffer(products: [], serviceNames: {});
}

DateTime _today() {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day);
}

OwnPass _pass(
  String name,
  PassStatus status, {
  int? total = 8,
  int? remaining = 5,
  required DateTime validFrom,
  required DateTime validUntil,
}) {
  return OwnPass(
    id: name,
    productName: name,
    entriesTotal: total,
    entriesRemaining: remaining,
    status: status,
    validFrom: validFrom,
    validUntil: validUntil,
  );
}

void main() {
  Future<ProviderContainer> pumpScreen(
    WidgetTester tester,
    PassesApi api, {
    Session session = _session,
  }) async {
    final container = ProviderContainer(
      overrides: [
        authControllerProvider.overrideWith(
          () => _SignedInAuthController(session),
        ),
        passesApiProvider.overrideWithValue(api),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: PassesScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  testWidgets('shows the remaining entries and validity of current passes', (
    tester,
  ) async {
    final today = _today();
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'self': [
            _pass(
              'Karnet Pole Dance',
              PassStatus.active,
              validFrom: DateTime(today.year, today.month, today.day - 3),
              validUntil: DateTime(today.year, today.month, today.day + 4),
            ),
            _pass(
              'Open',
              PassStatus.active,
              total: null,
              remaining: null,
              validFrom: today,
              validUntil: today,
            ),
          ],
        },
      ),
    );

    expect(find.text('Profil: Anna Kowalska'), findsOneWidget);
    expect(find.text('Aktualne'), findsOneWidget);
    expect(find.text('Pozostało wejść: 5 z 8'), findsOneWidget);
    expect(find.text('Zostało 5 dni'), findsOneWidget);
    expect(find.text('Bez limitu wejść'), findsOneWidget);
    expect(find.text('Ostatni dzień ważności'), findsOneWidget);
    expect(find.text('Aktywny'), findsNWidgets(2));
  });

  testWidgets('groups scheduled passes and collapses expired ones', (
    tester,
  ) async {
    final today = _today();
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'self': [
            _pass(
              'Next month',
              PassStatus.scheduled,
              validFrom: DateTime(2030, 11, 1),
              validUntil: DateTime(2030, 11, 30),
            ),
            _pass(
              'Old',
              PassStatus.expired,
              remaining: 0,
              validFrom: today.subtract(const Duration(days: 40)),
              validUntil: today.subtract(const Duration(days: 11)),
            ),
          ],
        },
      ),
    );

    expect(find.text('Zaplanowane'), findsOneWidget);
    expect(find.text('Ważny od 01.11.2030 do 30.11.2030'), findsOneWidget);
    expect(find.text('Wygasłe (1)'), findsOneWidget);
    expect(find.text('Old'), findsNothing);

    await tester.tap(find.text('Wygasłe (1)'));
    await tester.pumpAndSettle();

    expect(find.text('Old'), findsOneWidget);
    expect(find.text('Wykorzystano wszystkie wejścia'), findsOneWidget);
  });

  testWidgets('shows the passes of the selected child profile', (tester) async {
    final today = _today();
    final container = await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'child-1': [
            _pass(
              'Karnet dziecięcy',
              PassStatus.active,
              validFrom: today,
              validUntil: today.add(const Duration(days: 29)),
            ),
          ],
        },
      ),
    );
    expect(
      find.text('Nie masz jeszcze karnetu. Sprawdź ofertę studia.'),
      findsOneWidget,
    );

    container
        .read(authControllerProvider.notifier)
        .choose(
          SessionChoice(role: AppRole.client, profile: _session.profiles[1]),
        );
    await tester.pumpAndSettle();

    expect(find.text('Profil: Jan Kowalski'), findsOneWidget);
    expect(find.text('Karnet dziecięcy'), findsOneWidget);
  });

  testWidgets('offers a retry when the passes cannot be loaded', (
    tester,
  ) async {
    final api = _FakePassesApi(fails: true);
    await pumpScreen(tester, api);

    expect(find.text('Nie udało się pobrać karnetów.'), findsOneWidget);

    api.fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();

    expect(
      find.text('Nie masz jeszcze karnetu. Sprawdź ofertę studia.'),
      findsOneWidget,
    );
  });

  testWidgets('a trainer-only account has no passes', (tester) async {
    await pumpScreen(
      tester,
      _FakePassesApi(),
      session: const Session(
        accountId: 'trainer-user',
        displayName: 'Trainer',
        roles: [AppRole.trainer],
        activeRole: AppRole.trainer,
        staffProfileId: 'staff',
        profiles: [],
        activeProfileId: null,
      ),
    );

    expect(
      find.text('To konto nie ma profilu klienta, więc nie ma karnetów.'),
      findsOneWidget,
    );
  });

  testWidgets('the offer shows prices and rules of products on sale', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      _FakePassesApi(
        offer: const PassOffer(
          products: [
            PassProduct(
              id: 'p1',
              name: 'Karnet Pole Dance',
              description: 'Zajęcia grupowe',
              kind: PassProductKind.entryPass,
              audience: PassAudience.adult,
              suspensionAllowed: true,
              maxSuspensionDays: 14,
              serviceTypeIds: ['s1', 'unknown'],
              variants: [
                PassVariant(
                  id: 'v1',
                  entriesCount: 8,
                  validityDays: 30,
                  price: Money(amountMinor: 26000, currency: 'PLN'),
                ),
                PassVariant(
                  id: 'v2',
                  entriesCount: null,
                  validityDays: 30,
                  price: Money(amountMinor: 29900, currency: 'PLN'),
                ),
              ],
            ),
          ],
          serviceNames: {'s1': 'Pole dance - początkujący'},
        ),
      ),
    );

    await tester.tap(find.text('Oferta'));
    await tester.pumpAndSettle();

    expect(find.text('Karnet Pole Dance'), findsOneWidget);
    expect(find.text('Karnet na wejścia · Dla dorosłych'), findsOneWidget);
    expect(find.text('Zajęcia: Pole dance - początkujący'), findsOneWidget);
    expect(find.text('Można zawiesić maksymalnie na 14 dni'), findsOneWidget);
    expect(find.text('8 wejść, ważny 30 dni'), findsOneWidget);
    expect(find.text('260,00 PLN'), findsOneWidget);
    expect(find.text('Bez limitu wejść, ważny 30 dni'), findsOneWidget);
    expect(find.text('299,00 PLN'), findsOneWidget);
  });
}
