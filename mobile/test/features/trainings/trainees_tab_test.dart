import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/presentation/new_client_account_screen.dart';
import 'package:mobile/features/trainings/presentation/new_cooperation_screen.dart';
import 'package:mobile/features/trainings/presentation/trainings_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'trainer-account',
  displayName: 'Marek Nowak',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [],
  activeProfileId: null,
  permissions: {Session.customerAccountCreate},
);

class _SignedInAuthController extends AuthController {
  _SignedInAuthController([this.session = _session]);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

class _FakeTraineesApi implements TraineesApi {
  _FakeTraineesApi({
    List<Trainee> trainees = const [],
    this.customers = const [],
  }) : trainees = [...trainees];

  final List<Trainee> trainees;
  final List<CustomerSummary> customers;
  final queries = <String>[];
  bool fails = false;
  int? assignStatus;
  int? createStatus;
  final createdAccounts =
      <({String email, String firstName, String lastName})>[];

  @override
  Future<List<Trainee>> fetchOwnTrainees() async {
    if (fails) {
      throw const ApiException(statusCode: 500, message: 'Server error');
    }
    return List.of(trainees);
  }

  @override
  Future<List<CustomerSummary>> searchCustomers(String query) async {
    queries.add(query);
    return [
      for (final customer in customers)
        if (customer.displayName.toLowerCase().contains(query.toLowerCase()))
          customer,
    ];
  }

  @override
  Future<Trainee> assign(String customerId) async {
    final status = assignStatus;
    if (status != null) {
      throw ApiException(statusCode: status, message: 'Error');
    }
    final customer = customers.firstWhere((c) => c.id == customerId);
    final trainee = _trainee(customer.id, customer.displayName);
    trainees.add(trainee);
    return trainee;
  }

  @override
  Future<void> unassign(String customerId) async {
    trainees.removeWhere((t) => t.customerId == customerId);
  }

  @override
  Future<NewClientAccount> createClientAccount({
    required String email,
    required String firstName,
    required String lastName,
  }) async {
    final status = createStatus;
    if (status != null) {
      throw ApiException(statusCode: status, message: 'Error');
    }
    createdAccounts.add((
      email: email.trim(),
      firstName: firstName.trim(),
      lastName: lastName.trim(),
    ));
    final name = '${firstName.trim()} ${lastName.trim()}';
    trainees.add(_trainee('c-new', name, isAwaitingActivation: true));
    return NewClientAccount(
      customerId: 'c-new',
      email: email.trim(),
      displayName: name,
      expiresAt: DateTime(2026, 11, 10),
    );
  }
}

Trainee _trainee(
  String id,
  String name, {
  bool isChild = false,
  DateTime? birthDate,
  bool isAwaitingActivation = false,
}) => Trainee(
  customerId: id,
  displayName: name,
  isChild: isChild,
  birthDate: birthDate,
  isActive: true,
  assignedAt: DateTime(2026, 10, 1),
  isAwaitingActivation: isAwaitingActivation,
);

void main() {
  Future<void> pumpScreen(
    WidgetTester tester,
    TraineesApi api, {
    Session session = _session,
  }) async {
    final router = GoRouter(
      initialLocation: '/trainer/trainings',
      routes: [
        GoRoute(
          path: '/trainer/trainings',
          builder: (_, _) => const TrainingsScreen(),
          routes: [
            GoRoute(
              path: 'new-cooperation',
              builder: (_, _) => const NewCooperationScreen(),
            ),
            GoRoute(
              path: 'new-client',
              builder: (_, _) => const NewClientAccountScreen(),
            ),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(
            () => _SignedInAuthController(session),
          ),
          traineesApiProvider.overrideWithValue(api),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('lists the trainer\'s trainees in the Trainees tab', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      _FakeTraineesApi(
        trainees: [
          _trainee(
            'c-1',
            'Zosia Dąbrowska',
            isChild: true,
            birthDate: DateTime(2015, 5, 12),
          ),
          _trainee('c-2', 'Katarzyna Zielińska'),
        ],
      ),
    );

    expect(find.text('Podopieczni'), findsOneWidget);
    expect(find.text('Zosia Dąbrowska'), findsOneWidget);
    expect(find.text('Katarzyna Zielińska'), findsOneWidget);
    expect(find.textContaining('Dziecko · ur. 12.05.2015'), findsOneWidget);
    // Without a known date of birth, none is shown.
    expect(find.textContaining('ur.'), findsOneWidget);
    expect(find.text('Zarejestruj nową współpracę'), findsOneWidget);
  });

  testWidgets('shows an empty state and a retry after a failure', (
    tester,
  ) async {
    final api = _FakeTraineesApi()..fails = true;
    await pumpScreen(tester, api);

    expect(
      find.text('Nie udało się pobrać listy podopiecznych.'),
      findsOneWidget,
    );

    api.fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Nie masz jeszcze podopiecznych'),
      findsOneWidget,
    );
  });

  testWidgets('registers a new cooperation with a found customer', (
    tester,
  ) async {
    final api = _FakeTraineesApi(
      trainees: [_trainee('c-1', 'Zosia Dąbrowska')],
      customers: [
        CustomerSummary(
          id: 'c-1',
          displayName: 'Zosia Dąbrowska',
          isChild: false,
          birthDate: null,
        ),
        CustomerSummary(
          id: 'c-2',
          displayName: 'Katarzyna Zielińska',
          isChild: false,
          birthDate: DateTime(1990, 5, 1),
        ),
      ],
    );
    await pumpScreen(tester, api);

    await tester.tap(find.text('Zarejestruj nową współpracę'));
    await tester.pumpAndSettle();

    // The current trainee is shown, but cannot be chosen again.
    expect(find.text('Już współpracujecie'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'ziel');
    await tester.pump(NewCooperationScreen.searchDelay);
    await tester.pumpAndSettle();
    expect(api.queries.last, 'ziel');
    expect(find.text('Zosia Dąbrowska'), findsNothing);
    expect(find.text('ur. 01.05.1990'), findsOneWidget);

    await tester.tap(find.text('Katarzyna Zielińska'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Rozpocznij współpracę'));
    await tester.pumpAndSettle();

    expect(
      find.text('Zarejestrowano współpracę z: Katarzyna Zielińska.'),
      findsOneWidget,
    );
    // Back on the list, which now includes the new trainee.
    expect(find.byType(NewCooperationScreen), findsNothing);
    expect(find.text('Katarzyna Zielińska'), findsOneWidget);
  });

  testWidgets('explains why the cooperation was not registered', (
    tester,
  ) async {
    final api = _FakeTraineesApi(
      customers: [
        CustomerSummary(
          id: 'c-2',
          displayName: 'Adam Mazur',
          isChild: false,
          birthDate: null,
        ),
      ],
    )..assignStatus = 422;
    await pumpScreen(tester, api);

    await tester.tap(find.text('Zarejestruj nową współpracę'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Adam Mazur'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Rozpocznij współpracę'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Nie można rozpocząć współpracy'),
      findsOneWidget,
    );
    expect(find.byType(NewCooperationScreen), findsOneWidget);
  });

  testWidgets('ends a cooperation after confirmation', (tester) async {
    final api = _FakeTraineesApi(
      trainees: [_trainee('c-1', 'Zosia Dąbrowska')],
    );
    await pumpScreen(tester, api);

    await tester.tap(find.byTooltip('Więcej'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Zakończ współpracę'));
    await tester.pumpAndSettle();
    expect(find.text('Zakończyć współpracę?'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Zakończ współpracę'));
    await tester.pumpAndSettle();

    expect(api.trainees, isEmpty);
    expect(find.text('Zosia Dąbrowska'), findsNothing);
    expect(
      find.text('Zakończono współpracę z: Zosia Dąbrowska.'),
      findsOneWidget,
    );
  });
  testWidgets('creates a client account for a new trainee', (tester) async {
    final api = _FakeTraineesApi();
    await pumpScreen(tester, api);

    await tester.tap(find.text('Załóż konto klientowi'));
    await tester.pumpAndSettle();
    expect(find.byType(NewClientAccountScreen), findsOneWidget);
    expect(
      find.textContaining('e-mail z linkiem do ustawienia hasła'),
      findsOneWidget,
    );

    // Nothing is sent until the form is valid.
    await tester.tap(find.text('Załóż konto i wyślij zaproszenie'));
    await tester.pumpAndSettle();
    expect(find.text('To pole jest wymagane.'), findsNWidgets(3));
    await tester.enterText(find.byKey(const Key('newClientEmail')), 'julia@');
    await tester.pumpAndSettle();
    expect(find.text('Podaj poprawny adres e-mail.'), findsOneWidget);
    expect(api.createdAccounts, isEmpty);

    await tester.enterText(
      find.byKey(const Key('newClientFirstName')),
      ' Julia ',
    );
    await tester.enterText(
      find.byKey(const Key('newClientLastName')),
      'Kamińska',
    );
    await tester.enterText(
      find.byKey(const Key('newClientEmail')),
      'julia@example.com ',
    );
    await tester.tap(find.text('Załóż konto i wyślij zaproszenie'));
    await tester.pumpAndSettle();

    expect(api.createdAccounts.single, (
      email: 'julia@example.com',
      firstName: 'Julia',
      lastName: 'Kamińska',
    ));
    expect(
      find.text(
        'Założono konto: Julia Kamińska. Zaproszenie wysłano na julia@example.com.',
      ),
      findsOneWidget,
    );
    // Back on the list: the new trainee waits for the account activation.
    expect(find.byType(NewClientAccountScreen), findsNothing);
    expect(find.text('Julia Kamińska'), findsOneWidget);
    expect(find.text('Czeka na aktywację konta'), findsOneWidget);
  });

  testWidgets('points to the search when the e-mail already has an account', (
    tester,
  ) async {
    final api = _FakeTraineesApi()..createStatus = 409;
    await pumpScreen(tester, api);

    await tester.tap(find.text('Załóż konto klientowi'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('newClientFirstName')),
      'Julia',
    );
    await tester.enterText(
      find.byKey(const Key('newClientLastName')),
      'Kamińska',
    );
    await tester.enterText(
      find.byKey(const Key('newClientEmail')),
      'julia@example.com',
    );
    await tester.tap(find.text('Załóż konto i wyślij zaproszenie'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Konto z tym adresem e-mail już istnieje'),
      findsOneWidget,
    );

    await tester.ensureVisible(find.text('Znajdź na liście klientów'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Znajdź na liście klientów'));
    await tester.pumpAndSettle();
    expect(find.byType(NewCooperationScreen), findsOneWidget);
    expect(find.byType(NewClientAccountScreen), findsNothing);
  });

  testWidgets('explains that the invitation e-mail was not sent', (
    tester,
  ) async {
    final api = _FakeTraineesApi()..createStatus = 502;
    await pumpScreen(tester, api);

    await tester.tap(find.text('Załóż konto klientowi'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('newClientFirstName')),
      'Julia',
    );
    await tester.enterText(
      find.byKey(const Key('newClientLastName')),
      'Kamińska',
    );
    await tester.enterText(
      find.byKey(const Key('newClientEmail')),
      'julia@example.com',
    );
    await tester.tap(find.text('Załóż konto i wyślij zaproszenie'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Nie udało się wysłać zaproszenia'),
      findsOneWidget,
    );
    expect(find.text('Znajdź na liście klientów'), findsNothing);
    expect(find.byType(NewClientAccountScreen), findsOneWidget);
  });

  testWidgets('hides account creation without the permission', (tester) async {
    const withoutPermission = Session(
      accountId: 'trainer-account',
      displayName: 'Marek Nowak',
      roles: [AppRole.trainer],
      activeRole: AppRole.trainer,
      staffProfileId: 'staff-1',
      profiles: [],
      activeProfileId: null,
    );
    await pumpScreen(tester, _FakeTraineesApi(), session: withoutPermission);

    expect(find.text('Zarejestruj nową współpracę'), findsOneWidget);
    expect(find.text('Załóż konto klientowi'), findsNothing);

    await tester.tap(find.text('Zarejestruj nową współpracę'));
    await tester.pumpAndSettle();
    expect(find.text('Nie znaleziono klientów.'), findsOneWidget);
    expect(find.text('Nie ma tej osoby w studiu?'), findsNothing);
    expect(find.text('Załóż konto klientowi'), findsNothing);
  });

  testWidgets('the search leads to the new account form', (tester) async {
    await pumpScreen(tester, _FakeTraineesApi());

    await tester.tap(find.text('Zarejestruj nową współpracę'));
    await tester.pumpAndSettle();
    expect(find.text('Nie znaleziono klientów.'), findsOneWidget);
    expect(find.text('Nie ma tej osoby w studiu?'), findsOneWidget);

    await tester.tap(find.text('Załóż konto klientowi'));
    await tester.pumpAndSettle();
    expect(find.byType(NewClientAccountScreen), findsOneWidget);
    expect(find.byType(NewCooperationScreen), findsNothing);
  });
}
