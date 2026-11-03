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

  /// Orders placed, as `variantId|startsOn|startAfterPassId`.
  final List<String> orders = [];

  /// Orders paid with the simulated payment.
  final List<String> paidOrders = [];

  @override
  Future<PassOrder> placeOrder(
    String profileId, {
    required String variantId,
    DateTime? startsOn,
    String? startAfterPassId,
  }) async {
    orders.add('$variantId|${startsOn ?? ''}|${startAfterPassId ?? ''}');
    return _order('order-1', paid: false);
  }

  @override
  Future<PassOrder> payOrderWithMock(String orderId) async {
    paidOrders.add(orderId);
    return _order(orderId, paid: true);
  }

  static PassOrder _order(String id, {required bool paid}) {
    return PassOrder(
      id: id,
      paid: paid,
      productName: 'Karnet Pole Dance',
      price: const Money(amountMinor: 26000, currency: 'PLN'),
      passStatus: paid ? PassStatus.active : PassStatus.pendingPayment,
      validFrom: DateTime(2030, 1, 1),
      validUntil: DateTime(2030, 1, 30),
    );
  }
}

const _poleDanceOffer = PassOffer(
  products: [
    PassProduct(
      id: 'p1',
      name: 'Pole Dance',
      description: null,
      kind: PassProductKind.entryPass,
      audience: PassAudience.any,
      suspensionAllowed: false,
      maxSuspensionDays: null,
      serviceTypeIds: [],
      variants: [
        PassVariant(
          id: 'v1',
          entriesCount: 8,
          validityDays: 30,
          price: Money(amountMinor: 26000, currency: 'PLN'),
        ),
      ],
    ),
    PassProduct(
      id: 'p2',
      name: 'Karnet dziecięcy',
      description: null,
      kind: PassProductKind.entryPass,
      audience: PassAudience.child,
      suspensionAllowed: false,
      maxSuspensionDays: null,
      serviceTypeIds: [],
      variants: [
        PassVariant(
          id: 'v2',
          entriesCount: 4,
          validityDays: 30,
          price: Money(amountMinor: 16000, currency: 'PLN'),
        ),
      ],
    ),
  ],
  serviceNames: {},
);

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
  String? variantId,
  bool expiringSoon = false,
}) {
  return OwnPass(
    id: name,
    variantId: variantId,
    expiringSoon: expiringSoon,
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

    container.read(authControllerProvider.notifier).selectProfile('child-1');
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

  testWidgets('an expiring pass offers buying the same variant after it ends', (
    tester,
  ) async {
    final today = _today();
    final lastDay = DateTime(today.year, today.month, today.day + 3);
    final api = _FakePassesApi(
      passes: {
        'self': [
          _pass(
            'Karnet Pole Dance',
            PassStatus.active,
            remaining: 2,
            validFrom: DateTime(today.year, today.month, today.day - 26),
            validUntil: lastDay,
            variantId: 'v1',
            expiringSoon: true,
          ),
        ],
      },
      offer: _poleDanceOffer,
    );
    await pumpScreen(tester, api);

    expect(find.text('Karnet niedługo się kończy'), findsOneWidget);
    await tester.tap(find.text('Kup kolejny'));
    await tester.pumpAndSettle();

    expect(find.text('Zakup karnetu'), findsOneWidget);
    expect(find.text('Pole Dance'), findsOneWidget);
    expect(
      find.text('Rozpocznij po zakończeniu obecnego karnetu'),
      findsOneWidget,
    );
    final firstDay = DateTime(lastDay.year, lastDay.month, lastDay.day + 1);
    final until = DateTime(firstDay.year, firstDay.month, firstDay.day + 29);
    expect(
      find.text('Ważny od ${_date(firstDay)} do ${_date(until)}'),
      findsOneWidget,
    );
    final pay = find.byKey(const Key('checkout-pay'));
    expect(tester.widget<FilledButton>(pay).onPressed, isNull);

    await tester.tap(find.byKey(const Key('checkout-terms')));
    await tester.pump();
    await tester.tap(pay);
    await tester.pumpAndSettle();

    expect(api.orders, ['v1||Karnet Pole Dance']);
    expect(api.paidOrders, ['order-1']);
    expect(find.text('Zakup karnetu'), findsNothing);
    expect(
      find.text('Karnet opłacony. Ważny od 01.01.2030 do 30.01.2030.'),
      findsOneWidget,
    );
  });

  testWidgets('a pass followed by a bought one does not offer buying again', (
    tester,
  ) async {
    final today = _today();
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'self': [
            _pass(
              'Current',
              PassStatus.active,
              validFrom: DateTime(today.year, today.month, today.day - 26),
              validUntil: DateTime(today.year, today.month, today.day + 3),
              variantId: 'v1',
              expiringSoon: true,
            ),
            _pass(
              'Next',
              PassStatus.scheduled,
              validFrom: DateTime(today.year, today.month, today.day + 4),
              validUntil: DateTime(today.year, today.month, today.day + 33),
              variantId: 'v1',
            ),
          ],
        },
      ),
    );

    expect(find.text('Kolejny karnet już kupiony'), findsOneWidget);
    expect(find.text('Kup kolejny'), findsNothing);
  });

  testWidgets(
    'renewing a variant that is no longer on sale points to the offer',
    (tester) async {
      final today = _today();
      await pumpScreen(
        tester,
        _FakePassesApi(
          passes: {
            'self': [
              _pass(
                'Old',
                PassStatus.expired,
                validFrom: DateTime(today.year, today.month, today.day - 40),
                validUntil: DateTime(today.year, today.month, today.day - 11),
                variantId: 'retired',
              ),
            ],
          },
          offer: _poleDanceOffer,
        ),
      );
      await tester.tap(find.text('Wygasłe (1)'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Kup kolejny'));
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Ten karnet nie jest już w sprzedaży. Wybierz karnet w zakładce Oferta.',
        ),
        findsOneWidget,
      );
      expect(find.text('Zakup karnetu'), findsNothing);
    },
  );

  testWidgets('a pass from the offer starts on the chosen day', (tester) async {
    final api = _FakePassesApi(offer: _poleDanceOffer);
    await pumpScreen(tester, api);
    await tester.tap(find.text('Oferta'));
    await tester.pumpAndSettle();

    expect(
      find.text('Kup'),
      findsOneWidget,
      reason: 'no children pass for an adult',
    );
    await tester.tap(find.text('Kup'));
    await tester.pumpAndSettle();

    expect(
      find.text('Rozpocznij po zakończeniu obecnego karnetu'),
      findsNothing,
    );
    expect(find.text('Data rozpoczęcia'), findsOneWidget);
    expect(find.text(_date(_today())), findsOneWidget);

    await tester.tap(find.byKey(const Key('checkout-terms')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('checkout-pay')));
    await tester.pumpAndSettle();

    expect(api.orders, ['v1|${_today()}|']);
    expect(api.paidOrders, ['order-1']);
  });
}

String _date(DateTime date) =>
    '${date.day.toString().padLeft(2, '0')}.'
    '${date.month.toString().padLeft(2, '0')}.${date.year}';
