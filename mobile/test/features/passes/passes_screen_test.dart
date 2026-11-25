import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/features/passes/presentation/passes_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

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
  late List<PassUsageQuery> usageQueries;

  Future<ProviderContainer> pumpScreen(
    WidgetTester tester,
    PassesApi api, {
    Session session = _session,
    int makeups = 0,
  }) async {
    usageQueries = [];
    final container = ProviderContainer(
      overrides: [
        authControllerProvider.overrideWith(
          () => _SignedInAuthController(session),
        ),
        passesApiProvider.overrideWithValue(api),
        passUsageProvider.overrideWith((ref, query) async {
          usageQueries.add(query);
          return const PassUsagePage(
            items: [],
            page: 0,
            totalItems: 0,
            totalPages: 0,
          );
        }),
        makeupCreditsProvider.overrideWith(
          (ref, query) async => MakeupCreditPage(
            items: [
              for (var i = 0; i < makeups; i++)
                MakeupCredit(
                  id: 'm$i',
                  productName: 'Karnet Pole Dance',
                  expiresAt: DateTime(2035, 1, 1),
                ),
            ],
            page: 0,
            totalItems: makeups,
            totalPages: makeups == 0 ? 0 : 1,
          ),
        ),
        unreadCountProvider.overrideWith(
          (ref, scope) async => UnreadNotifications.none,
        ),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: buildClientTheme(Brightness.light),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          home: const PassesScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return container;
  }

  Finder rich(String text) => find.text(text, findRichText: true);

  testWidgets('shows one pass at a time, the one that ends first', (
    tester,
  ) async {
    final today = _today();
    final soon = DateTime(today.year, today.month, today.day + 4);
    final later = DateTime(today.year, today.month, today.day + 20);
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'self': [
            _pass(
              'Open',
              PassStatus.active,
              total: null,
              remaining: null,
              validFrom: today,
              validUntil: later,
            ),
            _pass(
              'Karnet Pole Dance',
              PassStatus.active,
              validFrom: DateTime(today.year, today.month, today.day - 3),
              validUntil: soon,
            ),
          ],
        },
      ),
    );

    expect(find.text('Karnet Pole Dance'), findsOneWidget);
    expect(
      find.text('8 wejść · ważny do ${formatPassDay(soon)}'),
      findsOneWidget,
    );
    expect(find.text('Aktywny'), findsOneWidget);
    expect(rich('Pozostało 5 z 8 wejść'), findsOneWidget);
    expect(
      find.text('ważny do ${formatPassDay(soon)} · pozostało 5 dni'),
      findsOneWidget,
    );
    expect(find.text('Open'), findsNothing);
    expect(find.text('Kup kolejny'), findsNothing);

    await tester.tap(find.text('Karnet Pole Dance'));
    await tester.pumpAndSettle();
    expect(find.text('Wybierz karnet'), findsOneWidget);
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();

    expect(find.text('Wybierz karnet'), findsNothing);
    expect(find.text('Open'), findsOneWidget);
    expect(find.text('Bez limitu wejść'), findsOneWidget);
    expect(rich('Pozostało 5 z 8 wejść'), findsNothing);
  });

  testWidgets('a single pass has no picker and a scheduled one its dates', (
    tester,
  ) async {
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
          ],
        },
      ),
    );

    expect(find.text('Zaplanowany'), findsOneWidget);
    expect(find.text('ważny od 1.11 do 30.11'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byType(PickerField),
        matching: find.byIcon(Icons.expand_more),
      ),
      findsNothing,
    );
  });

  testWidgets('opens the entry history of the selected pass', (tester) async {
    final today = _today();
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'self': [
            _pass(
              'Karnet Pole Dance',
              PassStatus.active,
              validFrom: today,
              validUntil: DateTime(2035, 12, 6),
            ),
          ],
        },
      ),
    );
    await tester.tap(find.text('Historia wejść'));
    await tester.pumpAndSettle();

    expect(find.text('Karnet Pole Dance · 8 wejść'), findsOneWidget);
    expect(find.text('Pozostało 5 z 8 wejść · ważny do 6.12'), findsOneWidget);
    expect(find.text('Nie ma jeszcze historii wejść.'), findsOneWidget);
    expect(usageQueries.last.passId, 'Karnet Pole Dance');
    expect(usageQueries.last.profileId, 'self');
    expect(usageQueries.last.accountId, 'parent-user');
    expect(usageQueries.last.includeUpcoming, isTrue);
  });

  testWidgets('collapses expired passes and opens their history', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'self': [
            _pass(
              'Old',
              PassStatus.expired,
              remaining: 0,
              validFrom: DateTime(2030, 10, 8),
              validUntil: DateTime(2030, 11, 6),
            ),
          ],
        },
      ),
    );

    expect(find.text('Nie masz teraz ważnego karnetu.'), findsOneWidget);
    expect(find.text('Wygasłe (1)'), findsOneWidget);
    expect(find.text('Old · 8 wejść'), findsNothing);

    await tester.tap(find.text('Wygasłe (1)'));
    await tester.pumpAndSettle();
    expect(find.text('Old · 8 wejść'), findsOneWidget);
    expect(find.text('8.10–6.11 · wykorzystano 8 z 8'), findsOneWidget);
    expect(find.text('Wygasł'), findsOneWidget);

    await tester.tap(find.text('Old · 8 wejść'));
    await tester.pumpAndSettle();
    expect(find.text('Wygasł 6.11 · wykorzystano 8 z 8'), findsOneWidget);
    expect(usageQueries.last.passId, 'Old');
  });

  testWidgets('a family account picks whose passes it sees', (tester) async {
    final today = _today();
    await pumpScreen(
      tester,
      _FakePassesApi(
        passes: {
          'child-1': [
            _pass(
              'Karnet dziecięcy',
              PassStatus.active,
              total: 4,
              remaining: 3,
              validFrom: today,
              validUntil: today.add(const Duration(days: 29)),
            ),
          ],
        },
      ),
    );
    expect(find.text('Anna Kowalska'), findsOneWidget);
    expect(find.text('Ty'), findsOneWidget);
    expect(
      find.text('Nie masz jeszcze karnetu. Sprawdź ofertę studia.'),
      findsOneWidget,
    );

    await tester.tap(find.text('Anna Kowalska'));
    await tester.pumpAndSettle();
    expect(find.text('Czyje karnety?'), findsOneWidget);
    expect(find.text('Twój profil'), findsOneWidget);
    await tester.tap(find.text('Jan Kowalski'));
    await tester.pumpAndSettle();

    expect(find.text('Dziecko'), findsOneWidget);
    expect(find.text('Karnet dziecięcy'), findsOneWidget);
    expect(rich('Pozostało 3 z 4 wejść'), findsOneWidget);
  });

  testWidgets('a new active profile shows its own passes again', (
    tester,
  ) async {
    final container = await pumpScreen(tester, _FakePassesApi());
    await tester.tap(find.text('Anna Kowalska'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Jan Kowalski'));
    await tester.pumpAndSettle();
    expect(find.text('Jan Kowalski'), findsOneWidget);

    final auth = container.read(authControllerProvider.notifier);
    auth.choose(
      SessionChoice(role: AppRole.client, profile: _session.profiles[1]),
    );
    await tester.pumpAndSettle();
    auth.choose(
      SessionChoice(role: AppRole.client, profile: _session.profiles[0]),
    );
    await tester.pumpAndSettle();
    expect(find.text('Anna Kowalska'), findsOneWidget);
    expect(find.text('Jan Kowalski'), findsNothing);
  });

  testWidgets('the make-up strip shows above the passes', (tester) async {
    await pumpScreen(tester, _FakePassesApi(), makeups: 2);
    expect(find.text('Masz 2 zajęcia do odrobienia'), findsOneWidget);
    await tester.tap(find.text('Dowiedz się więcej'));
    await tester.pumpAndSettle();
    expect(find.text('Do odrobienia'), findsOneWidget);
    expect(find.text('Wybierz nowe zajęcia'), findsNWidgets(2));
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

  testWidgets('without passes the button opens the offer', (tester) async {
    await pumpScreen(tester, _FakePassesApi(offer: _poleDanceOffer));
    await tester.tap(find.widgetWithText(AppButton, 'Kup karnet'));
    await tester.pumpAndSettle();
    expect(find.text('Pole Dance'), findsOneWidget);
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
                  price: Money(amountMinor: 29950, currency: 'PLN'),
                ),
              ],
            ),
          ],
          serviceNames: {'s1': 'Pole dance - początkujący'},
        ),
      ),
    );

    await tester.tap(find.text('Kup karnet').first);
    await tester.pumpAndSettle();

    expect(find.text('Karnet Pole Dance'), findsOneWidget);
    expect(find.text('Karnet na wejścia · dla dorosłych'), findsOneWidget);
    expect(find.text('Zajęcia: Pole dance - początkujący'), findsOneWidget);
    expect(find.text('Można zawiesić maksymalnie na 14 dni'), findsOneWidget);
    expect(rich('8 wejść · ważny 30 dni'), findsOneWidget);
    expect(find.text('260 zł'), findsOneWidget);
    expect(rich('Bez limitu wejść · ważny 30 dni'), findsOneWidget);
    expect(find.text('299,50 zł'), findsOneWidget);
    expect(find.text('Kup'), findsNWidgets(2));
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
    expect(find.text('Karnety'), findsOneWidget, reason: 'the back link');
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
    expect(tester.widget<AppButton>(pay).onPressed, isNull);

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

  testWidgets('buying the next pass shows only for an ending paid pass', (
    tester,
  ) async {
    final today = _today();
    final api = _FakePassesApi(
      passes: {
        'self': [
          _pass(
            'Current',
            PassStatus.active,
            validFrom: DateTime(today.year, today.month, today.day - 6),
            validUntil: DateTime(today.year, today.month, today.day + 23),
            variantId: 'v1',
          ),
          _pass(
            'Unpaid',
            PassStatus.pendingPayment,
            validFrom: DateTime(today.year, today.month, today.day + 34),
            validUntil: DateTime(today.year, today.month, today.day + 63),
            variantId: 'v1',
            expiringSoon: true,
          ),
        ],
      },
    );
    await pumpScreen(tester, api);
    expect(find.text('Current'), findsOneWidget);
    expect(find.text('Kup kolejny'), findsNothing);

    await tester.tap(find.text('Current'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Unpaid'));
    await tester.pumpAndSettle();
    expect(find.text('Czeka na płatność'), findsOneWidget);
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
                'Ending',
                PassStatus.active,
                remaining: 1,
                validFrom: DateTime(today.year, today.month, today.day - 20),
                validUntil: DateTime(today.year, today.month, today.day + 2),
                variantId: 'retired',
                expiringSoon: true,
              ),
            ],
          },
          offer: _poleDanceOffer,
        ),
      );

      await tester.tap(find.text('Kup kolejny'));
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Ten karnet nie jest już w sprzedaży. Wybierz karnet w zakładce Kup karnet.',
        ),
        findsOneWidget,
      );
      expect(find.text('Zakup karnetu'), findsNothing);
    },
  );

  testWidgets('the offer buys for the selected person', (tester) async {
    final api = _FakePassesApi(offer: _poleDanceOffer);
    await pumpScreen(tester, api);
    await tester.tap(find.text('Anna Kowalska'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Jan Kowalski'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Kup karnet').first);
    await tester.pumpAndSettle();

    expect(find.text('Kup'), findsNWidgets(2), reason: 'any and child passes');
    await tester.tap(find.text('Kup').last);
    await tester.pumpAndSettle();
    expect(find.text('Karnet dziecięcy'), findsOneWidget);
    expect(find.text('Dla: Jan Kowalski'), findsOneWidget);
  });

  testWidgets('a pass from the offer starts on the chosen day', (tester) async {
    final api = _FakePassesApi(offer: _poleDanceOffer);
    await pumpScreen(tester, api);
    await tester.tap(find.text('Kup karnet').first);
    await tester.pumpAndSettle();

    expect(
      find.text('Kup'),
      findsOneWidget,
      reason: 'no children pass for an adult',
    );
    await tester.tap(find.text('Kup'));
    await tester.pumpAndSettle();

    expect(find.text('Kup karnet'), findsOneWidget, reason: 'the back link');
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
