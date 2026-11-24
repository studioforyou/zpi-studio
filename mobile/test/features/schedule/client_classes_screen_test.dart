import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/schedule/presentation/client/classes_bars.dart';
import 'package:mobile/features/schedule/presentation/client/client_classes_screen.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

import '../trainings/fake_group_classes_api.dart';
import '../trainings/group_class_fixtures.dart';

const _scope = (accountId: 'parent', profileId: 'self');
const _session = Session(
  accountId: 'parent',
  displayName: 'Anna Klient',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'self', displayName: 'Anna Klient', isChild: false),
    Profile(id: 'child', displayName: 'Jan Klient', isChild: true),
  ],
  activeProfileId: 'self',
);

final _child = SessionChoice(
  role: AppRole.client,
  profile: _session.profiles[1],
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
}

class _ServiceTypesApi implements ScheduleServiceTypesApi {
  const _ServiceTypesApi(this.types);

  final List<ScheduleServiceType> types;

  @override
  Future<Map<String, ScheduleServiceType>> fetchAll() async => {
    for (final type in types) type.id: type,
  };
}

class _TrainersApi implements StudioTrainersApi {
  const _TrainersApi(this.trainers);

  final List<StudioTrainer> trainers;

  @override
  Future<List<StudioTrainer>> fetchAll() async => trainers;
}

/// A second class, by another trainer and of another type.
Map<String, dynamic> _strength() => {
  ...groupClassJson(title: 'Trening siłowy'),
  'id': 'class-2',
  'serviceTypeId': 'service-2',
  'serviceTypeName': 'Kalistenika',
  'startsAt': '2030-11-02T19:00:00Z',
  'endsAt': '2030-11-02T20:00:00Z',
  'staff': [
    {'staffId': 'staff-2', 'displayName': 'Marek Trener', 'role': 'LEAD'},
  ],
};

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  Future<ProviderContainer> pump(
    WidgetTester tester,
    FakeGroupClassesApi api, {
    List<ScheduleServiceType> serviceTypes = const [],
    List<StudioTrainer> trainers = const [],
    DateTime? now,
  }) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final container = ProviderContainer(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        groupClassesApiProvider.overrideWithValue(api),
        personalTrainingsProvider.overrideWith((ref, query) async => const []),
        scheduleServiceTypesApiProvider.overrideWithValue(
          _ServiceTypesApi(serviceTypes),
        ),
        studioTrainersApiProvider.overrideWithValue(_TrainersApi(trainers)),
        // series_change_test covers the changes strip.
        scheduleChangesProvider.overrideWith((ref, owner) => Stream.value([])),
        unreadCountProvider.overrideWith(
          (ref, scope) async => UnreadNotifications.none,
        ),
      ],
    );
    addTearDown(container.dispose);
    final router = GoRouter(
      initialLocation: clientSchedulePath,
      routes: [
        GoRoute(
          path: clientSchedulePath,
          builder: (_, _) => ClientClassesScreen(
            today: DateTime(2030, 11, 2),
            now: now ?? DateTime(2030, 11, 2, 22),
          ),
          routes: [
            GoRoute(
              path: clientClassDetailsSubPath,
              pageBuilder: (_, state) => AppSheetPage<GroupClassSheetResult>(
                key: state.pageKey,
                child: GroupClassDetailsScreen(
                  query: (
                    scope: bookingScope(
                      container.read(authControllerProvider),
                    )!,
                    eventId: state.pathParameters['eventId']!,
                  ),
                ),
              ),
            ),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          routerConfig: router,
          theme: buildClientTheme(Brightness.light),
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      ),
    );
    // A pending request keeps a progress animation alive.
    if (api.pendingSelf != null) {
      await tester.pump();
    } else {
      await tester.pumpAndSettle();
    }
    return container;
  }

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder.first);
    await tester.pumpAndSettle();
    await tester.tap(finder.first);
    await tester.pumpAndSettle();
  }

  Future<void> showAll(WidgetTester tester) =>
      tap(tester, find.text('Wszystkie'));

  Finder inSheet(Finder finder) =>
      find.descendant(of: find.byType(BottomSheet), matching: finder);

  Future<void> openFilters(WidgetTester tester, [int active = 0]) =>
      tap(tester, find.byTooltip(active == 0 ? 'Filtry' : 'Filtry ($active)'));

  Future<void> closeFilters(WidgetTester tester) =>
      tap(tester, find.byTooltip('Zamknij filtry'));

  /// Ticks [label] in the collapsible [section] of the filter sheet.
  Future<void> check(WidgetTester tester, String section, String label) async {
    await tap(tester, inSheet(find.textContaining(section)));
    await tap(tester, find.widgetWithText(CheckboxListTile, label));
  }

  testWidgets('Moje lists booked classes, Wszystkie the whole offer', (
    tester,
  ) async {
    final api = FakeGroupClassesApi(data: groupClassJson(reserved: true))
      ..extraClasses.add(_strength());
    await pump(tester, api);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Trening siłowy'), findsNothing);
    // Two weeks of own reservations, from the Monday of today.
    expect(api.queries.first.window, (
      from: DateTime(2030, 10, 28),
      to: DateTime(2030, 11, 4),
    ));
    expect(api.reservationReads, 2);

    await showAll(tester);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Trening siłowy'), findsOneWidget);
    expect(find.text('MASZ REZERWACJĘ'), findsOneWidget);
  });

  testWidgets('the next class shows in a bar that opens it', (tester) async {
    await pump(
      tester,
      FakeGroupClassesApi(data: groupClassJson(reserved: true)),
      now: DateTime(2030, 11, 2, 16, 30),
    );
    expect(find.byType(NextClassBar), findsOneWidget);
    expect(find.text('Dziś 17:00 · za 30 min'), findsOneWidget);
    await tap(tester, find.byType(NextClassBar));
    expect(find.byType(GroupClassDetailsScreen), findsOneWidget);
    expect(find.text('2 zajęć'), findsNothing);
  });

  testWidgets('Moje without classes points to Wszystkie', (tester) async {
    await pump(tester, FakeGroupClassesApi());
    expect(find.textContaining('W tych dniach nie masz zajęć'), findsOneWidget);
  });

  testWidgets('the list shows the next week on request', (tester) async {
    final api = FakeGroupClassesApi(data: groupClassJson(reserved: true));
    await pump(tester, api);
    final reads = api.queries.length;
    await tap(tester, find.text('Pokaż kolejny tydzień'));
    expect(api.queries.length, greaterThan(reads));
    expect(api.queries.last.window.from, DateTime(2030, 11, 11));
  });

  testWidgets('the calendar expands into the month and back', (tester) async {
    await pump(
      tester,
      FakeGroupClassesApi(data: groupClassJson(reserved: true)),
    );
    await tap(tester, find.byIcon(Icons.expand_more));
    expect(find.text('Listopad 2030'), findsOneWidget);
    await tap(tester, find.byIcon(Icons.expand_less));
    expect(find.text('Listopad 2030'), findsNothing);
  });

  testWidgets('a waiting position shows next to the class', (tester) async {
    await pump(
      tester,
      FakeGroupClassesApi(
        data: fullClassJson(waitlistPosition: 2, waitingCount: 3),
      ),
      now: DateTime(2030, 11, 2, 8),
    );
    await showAll(tester);
    expect(find.text('lista · 2.'), findsOneWidget);
  });

  testWidgets('service and trainer filters keep only matching classes', (
    tester,
  ) async {
    final api = FakeGroupClassesApi()..extraClasses.add(_strength());
    await pump(tester, api);
    await showAll(tester);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Trening siłowy'), findsOneWidget);

    await openFilters(tester);
    await check(tester, 'Rodzaj zajęć', 'Kalistenika');
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsNothing);
    expect(find.text('Trening siłowy'), findsOneWidget);
    expect(find.byTooltip('Filtry (1)'), findsOneWidget);

    await openFilters(tester, 1);
    await tap(tester, inSheet(find.text('Wyczyść')));
    await check(tester, 'Prowadzący', 'Anna Trener');
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Trening siłowy'), findsNothing);
  });

  testWidgets('free places only hides full classes', (tester) async {
    await pump(
      tester,
      FakeGroupClassesApi(data: groupClassJson(availablePlaces: 0)),
    );
    await showAll(tester);
    await openFilters(tester);
    await tap(tester, find.text('Tylko wolne miejsca'));
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsNothing);
    expect(
      find.text('Żadne zajęcia w tych dniach nie pasują do filtrów.'),
      findsOneWidget,
    );
  });

  testWidgets('level and age group filters use the service of each class', (
    tester,
  ) async {
    final api = FakeGroupClassesApi()
      ..extraClasses.add({
        ...groupClassJson(title: 'Akrobatyka dla dzieci'),
        'id': 'class-2',
        'serviceTypeId': 'service-2',
        'serviceTypeName': 'Akrobatyka',
        'startsAt': '2030-11-02T19:00:00Z',
        'endsAt': '2030-11-02T20:00:00Z',
      });
    await pump(
      tester,
      api,
      serviceTypes: const [
        ScheduleServiceType(
          category: 'GROUP_CLASS',
          id: 'service-1',
          level: 'BEGINNER',
          minAge: 16,
          maxAge: null,
        ),
        ScheduleServiceType(
          category: 'GROUP_CLASS',
          id: 'service-2',
          level: 'ADVANCED',
          minAge: 7,
          maxAge: 12,
        ),
      ],
    );
    await showAll(tester);
    await openFilters(tester);
    await check(tester, 'Poziom', 'Zaawansowany');
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsNothing);
    expect(find.text('Akrobatyka dla dzieci'), findsOneWidget);

    await openFilters(tester, 1);
    await tap(tester, inSheet(find.text('Wyczyść')));
    await tap(tester, find.widgetWithText(FilterChip, 'Dorośli'));
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Akrobatyka dla dzieci'), findsNothing);
  });

  testWidgets('pull to refresh fetches the same weeks again', (tester) async {
    final api = FakeGroupClassesApi(data: groupClassJson(reserved: true));
    await pump(tester, api);
    final windows = {for (final query in api.queries) query.window};
    final reads = api.reservationReads;

    api.data = groupClassJson(title: 'Trening po odświeżeniu', reserved: true);
    await tester.drag(find.byType(RefreshIndicator), const Offset(0, 900));
    await tester.pumpAndSettle();

    expect(api.reservationReads, greaterThan(reads));
    expect({for (final query in api.queries) query.window}, windows);
    expect(find.text('Trening po odświeżeniu'), findsOneWidget);
    expect(find.text('Pole dance'), findsNothing);
  });

  testWidgets('a failed load retries and then shows the empty list', (
    tester,
  ) async {
    final api = FakeGroupClassesApi()..fails = true;
    await pump(tester, api);
    expect(
      find.text('Nie udało się pobrać zajęć. Spróbuj ponownie.'),
      findsOneWidget,
    );
    api
      ..fails = false
      ..empty = true;
    await tap(tester, find.text('Spróbuj ponownie'));
    expect(
      find.text('Nie udało się pobrać zajęć. Spróbuj ponownie.'),
      findsNothing,
    );
    expect(find.textContaining('W tych dniach nie masz zajęć'), findsOneWidget);
  });

  testWidgets('a late answer for the parent does not replace the child', (
    tester,
  ) async {
    final pending = Completer<List<GroupClass>>();
    final api = FakeGroupClassesApi()..pendingSelf = pending;
    final container = await pump(tester, api);
    container.read(authControllerProvider.notifier).choose(_child);
    await tester.pumpAndSettle();
    await showAll(tester);
    expect(find.text('Zajęcia dziecięce'), findsOneWidget);
    pending.complete([
      GroupClass.fromJson(groupClassJson(title: 'Stara odpowiedź')),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('Zajęcia dziecięce'), findsOneWidget);
    expect(find.text('Stara odpowiedź'), findsNothing);
  });

  testWidgets('a class opens as a sheet; cancelling it updates Moje', (
    tester,
  ) async {
    final api = FakeGroupClassesApi(data: groupClassJson(reserved: true));
    await pump(tester, api);
    await tap(tester, find.text('Pole dance'));
    expect(find.byType(GroupClassDetailsScreen), findsOneWidget);
    final reads = api.reservationReads;

    await tap(tester, find.widgetWithText(AppButton, 'Wypisz się'));
    await tap(tester, find.widgetWithText(AppButton, 'Wypisz się').last);
    expect(api.cancellations, 1);
    await tap(tester, find.byTooltip('Zamknij'));
    expect(find.byType(GroupClassDetailsScreen), findsNothing);
    expect(api.reservationReads, greaterThan(reads));
    expect(find.text('Pole dance'), findsNothing);
  });

  testWidgets('a class cancelled by the studio leads to Wszystkie', (
    tester,
  ) async {
    final api = FakeGroupClassesApi(
      data: {...groupClassJson(reserved: true), 'status': 'CANCELLED'},
    )..extraClasses.add(_strength());
    await pump(tester, api);
    await tap(tester, find.text('Pole dance'));
    await tap(tester, find.widgetWithText(AppButton, 'Wybierz nowe zajęcia'));
    expect(find.byType(GroupClassDetailsScreen), findsNothing);
    expect(find.text('Trening siłowy'), findsOneWidget);
  });

  testWidgets('the opened class belongs to the selected profile', (
    tester,
  ) async {
    final api = FakeGroupClassesApi();
    await pump(tester, api);
    await showAll(tester);
    await tap(tester, find.text('Pole dance'));
    await tap(tester, find.widgetWithText(AppButton, 'Zapisz się'));
    expect(api.bookedQuery, (scope: _scope, eventId: 'class-1'));
  });
}
