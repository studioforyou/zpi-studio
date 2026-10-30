import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_screen.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

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

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
}

class _Api implements GroupClassesApi {
  _Api({Map<String, dynamic>? data}) : data = data ?? groupClassJson();

  Map<String, dynamic> data;
  bool fails = false;
  bool empty = false;
  bool cancellationConflict = false;
  bool cancellationReturned = true;
  Completer<List<GroupClass>>? pendingSelf;
  String? bookedPassId;
  ClassQuery? bookedQuery;
  int cancellations = 0;
  bool? expectedEntryReturned;
  int reservationReads = 0;
  final List<String> reads = [];
  final List<ClassesQuery> queries = [];
  final List<Map<String, dynamic>> extraClasses = [];
  int participantReads = 0;
  bool participantsFail = false;

  @override
  Future<List<ClassParticipant>> fetchParticipants(ClassQuery query) async {
    participantReads++;
    if (participantsFail) throw const ClassBookingException(statusCode: 403);
    return const [
      ClassParticipant(displayName: 'Anna Klient', isSelf: true),
      ClassParticipant(displayName: 'Maria Nowak', isSelf: false),
    ];
  }

  @override
  Future<List<GroupClass>> fetchClasses(ClassesQuery query) async {
    reads.add(query.scope.profileId);
    queries.add(query);
    if (fails) throw const ClassBookingException(statusCode: 503);
    if (query.scope.profileId == 'self' && pendingSelf != null) {
      return pendingSelf!.future;
    }
    if (empty) return [];
    return [
          for (final item in [data, ...extraClasses])
            GroupClass.fromJson({
              ...item,
              if (query.scope.profileId == 'child')
                'title': 'Zajęcia dziecięce',
            }),
        ]
        .where(
          (event) =>
              event.startsAt.isBefore(query.window.to) &&
              event.endsAt.isAfter(query.window.from),
        )
        .toList();
  }

  @override
  Future<List<GroupClass>> fetchReservations(ClassesQuery query) async {
    reservationReads++;
    return (await fetchClasses(
      query,
    )).where((event) => event.isReserved).toList();
  }

  @override
  Future<GroupClass> fetchClass(ClassQuery query) async {
    if (fails) throw const ClassBookingException(statusCode: 503);
    return GroupClass.fromJson(data);
  }

  @override
  Future<ClassReservation> reserve(
    ClassQuery query,
    String customerPassId,
  ) async {
    bookedPassId = customerPassId;
    bookedQuery = query;
    data = groupClassJson(reserved: true);
    return ClassReservation.fromJson(reservationJson());
  }

  @override
  Future<ClassReservation> cancel(
    BookingScope scope,
    ClassReservation reservation,
  ) async {
    cancellations++;
    expectedEntryReturned = reservation.cancellation.entryReturned;
    if (cancellationConflict) {
      cancellationConflict = false;
      data = groupClassJson(reserved: true, entryReturned: false);
      throw const ClassBookingException(
        statusCode: 409,
        code: 'CANCELLATION_CONSEQUENCE_CHANGED',
      );
    }
    data = groupClassJson();
    return ClassReservation.fromJson(
      reservationJson(active: false, entryReturned: cancellationReturned),
    );
  }
}

void main() {
  Future<ProviderContainer> pump(
    WidgetTester tester,
    _Api api, {
    bool details = false,
    bool reservations = false,
    bool phoneSize = false,
  }) async {
    if (!phoneSize) {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
    }
    final container = ProviderContainer(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        groupClassesApiProvider.overrideWithValue(api),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: details
              ? const GroupClassDetailsScreen(
                  query: (scope: _scope, eventId: 'class-1'),
                )
              : ClientScheduleScreen(
                  reservationsOnly: reservations,
                  today: DateTime(2030, 11, 2),
                ),
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

  Future<void> tapVisible(WidgetTester tester, Finder finder) async {
    if (finder.evaluate().isEmpty) {
      await tester.scrollUntilVisible(
        finder,
        160,
        scrollable: find.byType(Scrollable).first,
      );
    }
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  Future<void> closeFilters(WidgetTester tester) async {
    Navigator.of(tester.element(find.byType(BottomSheet))).pop();
    await tester.pumpAndSettle();
  }

  testWidgets(
    'offer shows class details and actual vacancies, full filter works',
    (tester) async {
      final api = _Api(data: groupClassJson(availablePlaces: 0));
      await pump(tester, api);
      expect(find.textContaining('Anna Trener'), findsOneWidget);
      expect(find.textContaining('Sala Turkusowa'), findsOneWidget);
      expect(find.textContaining('Brak miejsc'), findsOneWidget);
      await tapVisible(tester, find.text('Filtry'));
      await tapVisible(tester, find.text('Tylko wolne miejsca'));
      await closeFilters(tester);
      expect(find.text('Pole dance'), findsNothing);
      expect(find.text('Filtry (1)'), findsOneWidget);
      await tapVisible(tester, find.text('Wyczyść'));
      expect(find.text('Pole dance'), findsOneWidget);
    },
  );

  testWidgets(
    'service and instructor filters keep only the matching client classes',
    (tester) async {
      final api = _Api()
        ..extraClasses.add({
          ...groupClassJson(title: 'Trening siłowy'),
          'id': 'class-2',
          'serviceTypeId': 'service-2',
          'serviceTypeName': 'Kalistenika',
          'calendarColor': '#2196F3',
          'startsAt': '2030-11-02T19:00:00Z',
          'endsAt': '2030-11-02T20:00:00Z',
          'staff': [
            {
              'staffId': 'staff-2',
              'displayName': 'Marek Trener',
              'role': 'LEAD',
            },
          ],
        });
      await pump(tester, api);
      expect(find.text('Pole dance'), findsOneWidget);
      expect(find.text('Trening siłowy'), findsOneWidget);
      await tapVisible(tester, find.text('Filtry'));
      await tester.tap(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text('Kalistenika'),
        ),
      );
      await tester.pumpAndSettle();
      await closeFilters(tester);
      expect(find.text('Pole dance'), findsNothing);
      expect(find.text('Trening siłowy'), findsOneWidget);
      await tapVisible(tester, find.text('Wyczyść'));
      await tapVisible(tester, find.text('Filtry'));
      await tester.tap(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text('Anna Trener'),
        ),
      );
      await tester.pumpAndSettle();
      await closeFilters(tester);
      expect(find.text('Pole dance'), findsOneWidget);
      expect(find.text('Trening siłowy'), findsNothing);
    },
  );

  testWidgets(
    'selected filter names remain recognizable in a period without those classes',
    (tester) async {
      final api = _Api();
      await pump(tester, api);
      await tapVisible(tester, find.text('Filtry'));
      await tapVisible(
        tester,
        find.widgetWithText(FilterChip, 'Pole dance dla początkujących'),
      );
      await tapVisible(tester, find.widgetWithText(FilterChip, 'Anna Trener'));
      await closeFilters(tester);
      expect(find.text('Filtry (2)'), findsOneWidget);

      await tapVisible(tester, find.byTooltip('Następny'));
      expect(find.text('Pole dance'), findsNothing);
      await tapVisible(tester, find.text('Filtry (2)'));
      for (final label in ['Pole dance dla początkujących', 'Anna Trener']) {
        final choice = find.widgetWithText(FilterChip, label);
        expect(choice, findsOneWidget);
        expect(tester.widget<FilterChip>(choice).selected, isTrue);
      }
      await closeFilters(tester);

      await tapVisible(tester, find.byTooltip('Poprzedni'));
      expect(find.text('Pole dance'), findsOneWidget);
      expect(find.text('Filtry (2)'), findsOneWidget);
    },
  );

  testWidgets(
    'requires explicit choice between eligible passes and reserves selected profile',
    (tester) async {
      final api = _Api(
        data: groupClassJson(availablePlaces: null, passCount: 2),
      );
      await pump(tester, api, details: true);
      expect(find.text('Bez limitu miejsc'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Zapisz się'));
      expect(find.text('Uczestnik: Anna Klient'), findsNWidgets(2));
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Zapisz się').last,
            )
            .onPressed,
        isNull,
      );
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('Karnet 2').last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Zapisz się').last);
      await tester.pumpAndSettle();
      expect(api.bookedPassId, 'pass-2');
      expect(api.bookedQuery?.scope, _scope);
      expect(find.text('Masz rezerwację'), findsOneWidget);
      expect(find.text('Zapisano na zajęcia.'), findsOneWidget);
    },
  );

  testWidgets('explains unavailable booking and disables action', (
    tester,
  ) async {
    await pump(
      tester,
      _Api(
        data: groupClassJson(
          allowed: false,
          reason: 'REQUIRED_DOCUMENTS_MISSING',
          passCount: 0,
        ),
      ),
      details: true,
    );
    expect(
      find.textContaining('zaakceptować wymagane dokumenty'),
      findsOneWidget,
    );
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, 'Zapisz się'))
          .onPressed,
      isNull,
    );
  });

  testWidgets(
    'changing profile while confirming never submits old participant booking',
    (tester) async {
      final api = _Api();
      final container = await pump(tester, api, details: true);
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Zapisz się'));
      container.read(authControllerProvider.notifier).selectProfile('child');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Zapisz się').last);
      await tester.pumpAndSettle();
      expect(api.bookedPassId, isNull);
      expect(
        find.textContaining('Zmieniono profil uczestnika'),
        findsOneWidget,
      );
    },
  );

  testWidgets('late parent response does not replace selected child offer', (
    tester,
  ) async {
    final pending = Completer<List<GroupClass>>();
    final api = _Api()..pendingSelf = pending;
    final container = await pump(tester, api);
    container.read(authControllerProvider.notifier).selectProfile('child');
    await tester.pumpAndSettle();
    expect(find.text('Zajęcia dziecięce'), findsOneWidget);
    pending.complete([
      GroupClass.fromJson(groupClassJson(title: 'Stara odpowiedź')),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('Zajęcia dziecięce'), findsOneWidget);
    expect(find.text('Stara odpowiedź'), findsNothing);
  });

  testWidgets(
    'deadline conflict refreshes consequences and requires a new confirmation',
    (tester) async {
      final api = _Api(data: groupClassJson(reserved: true))
        ..cancellationConflict = true
        ..cancellationReturned = false;
      await pump(tester, api, details: true);
      await tapVisible(
        tester,
        find.widgetWithText(OutlinedButton, 'Odwołaj rezerwację'),
      );
      expect(find.text('Odwołasz bez utraty wejścia.'), findsOneWidget);
      await tester.tap(find.widgetWithText(FilledButton, 'Odwołaj rezerwację'));
      await tester.pumpAndSettle();
      expect(api.cancellations, 1);
      expect(api.expectedEntryReturned, isTrue);
      expect(
        find.textContaining('Warunki rezerwacji lub odwołania zmieniły się'),
        findsOneWidget,
      );
      await tapVisible(
        tester,
        find.widgetWithText(OutlinedButton, 'Odwołaj rezerwację'),
      );
      expect(
        find.text('Odwołanie po terminie — bez zwrotu wejścia.'),
        findsNWidgets(2),
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Odwołaj rezerwację'));
      await tester.pumpAndSettle();
      expect(api.cancellations, 2);
      expect(api.expectedEntryReturned, isFalse);
      expect(find.text('Rezerwacja odwołana.'), findsOneWidget);
    },
  );

  testWidgets(
    'own reservations load separately and booking mutation refreshes offer cache',
    (tester) async {
      final api = _Api(data: groupClassJson(reserved: true));
      final container = await pump(tester, api, reservations: true);
      expect(api.reservationReads, 1);
      expect(find.text('Masz rezerwację'), findsOneWidget);
      await tapVisible(tester, find.text('Pole dance'));
      await tapVisible(
        tester,
        find.widgetWithText(OutlinedButton, 'Odwołaj rezerwację'),
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Odwołaj rezerwację'));
      await tester.pumpAndSettle();
      Navigator.of(tester.element(find.byType(GroupClassDetailsScreen))).pop();
      await tester.pumpAndSettle();
      expect(find.text('Pole dance'), findsNothing);
      expect(api.reservationReads, greaterThan(1));
      expect(container.read(authControllerProvider), isA<Authenticated>());
    },
  );

  for (final reservations in [false, true]) {
    final section = reservations ? 'own reservations' : 'offer';

    testWidgets('$section pull to refresh fetches fresh API data', (
      tester,
    ) async {
      final api = _Api(data: groupClassJson(reserved: true));
      await pump(tester, api, reservations: reservations);
      expect(api.reads, ['self']);
      final originalWindow = api.queries.single.window;
      expect(find.text('Pole dance'), findsOneWidget);

      api.data = groupClassJson(
        title: 'Trening po odświeżeniu',
        reserved: true,
      );
      await tester.drag(find.byType(RefreshIndicator), const Offset(0, 900));
      await tester.pumpAndSettle();

      expect(api.reads, ['self', 'self']);
      expect(api.queries.last.window, originalWindow);
      expect(api.reservationReads, reservations ? 2 : 0);
      expect(find.text('Trening po odświeżeniu'), findsOneWidget);
      expect(find.text('Pole dance'), findsNothing);
    });

    testWidgets('$section retries a cached API error and shows empty state', (
      tester,
    ) async {
      final api = _Api()..fails = true;
      await pump(tester, api, reservations: reservations);
      expect(api.reads, ['self']);
      final originalWindow = api.queries.single.window;
      expect(find.text('Nie udało się pobrać grafiku.'), findsOneWidget);

      api.fails = false;
      api.empty = true;
      await tapVisible(tester, find.text('Spróbuj ponownie'));

      expect(api.reads, ['self', 'self']);
      expect(api.queries.last.window, originalWindow);
      expect(api.reservationReads, reservations ? 2 : 0);
      expect(find.text('Nie udało się pobrać grafiku.'), findsNothing);
      expect(find.text('Pole dance'), findsNothing);
      expect(find.byType(RefreshIndicator), findsOneWidget);
      expect(find.byType(ScheduleCalendar), findsOneWidget);
    });
  }

  testWidgets(
    'client uses the shared day week and month navigation and opens details',
    (tester) async {
      final api = _Api();
      await pump(tester, api);
      expect(find.byType(ScheduleCalendar), findsOneWidget);
      expect(
        tester
            .widget<SegmentedButton<ScheduleView>>(
              find.byType(SegmentedButton<ScheduleView>),
            )
            .selected,
        {ScheduleView.week},
      );
      expect(find.text('Pole dance'), findsOneWidget);
      expect(api.queries.last.window, (
        from: DateTime(2030, 10, 28),
        to: DateTime(2030, 11, 4),
      ));
      await tester.tap(find.text('Dzień'));
      await tester.pumpAndSettle();
      expect(api.queries.last.window, (
        from: DateTime(2030, 11, 2),
        to: DateTime(2030, 11, 3),
      ));
      expect(find.text('Pole dance'), findsOneWidget);
      await tester.tap(find.byTooltip('Następny'));
      await tester.pumpAndSettle();
      expect(api.queries.last.window.from, DateTime(2030, 11, 3));
      expect(find.text('Pole dance'), findsNothing);
      await tester.tap(find.byTooltip('Poprzedni'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Miesiąc'));
      await tester.pumpAndSettle();
      final monthWindow = api.queries.last.window;
      expect(monthWindow.from, DateTime(2030, 10, 28));
      expect(monthWindow.to, DateTime(2030, 12, 9));
      expect(find.text('Pole dance'), findsOneWidget);
      await tester.tap(find.text('3').first);
      await tester.pumpAndSettle();
      expect(find.text('Pole dance'), findsNothing);
      await tester.tap(find.text('2').first);
      await tester.pumpAndSettle();
      expect(find.text('Pole dance'), findsOneWidget);
      await tapVisible(tester, find.text('Pole dance'));
      expect(find.byType(GroupClassDetailsScreen), findsOneWidget);
    },
  );

  testWidgets(
    'unbooked class explains participant access without fetching names',
    (tester) async {
      final api = _Api();
      await pump(tester, api, details: true);
      final hint = find.text(
        'Lista uczestników będzie dostępna po zapisie na zajęcia.',
      );
      await tester.scrollUntilVisible(
        hint,
        160,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      expect(hint, findsOneWidget);
      expect(api.participantReads, 0);
    },
  );

  testWidgets(
    'active reservation exposes names and safe retry for roster errors',
    (tester) async {
      final api = _Api(data: groupClassJson(reserved: true))
        ..participantsFail = true;
      await pump(tester, api, details: true);
      final error = find.text('Nie udało się pobrać listy uczestników.');
      await tester.scrollUntilVisible(
        error,
        160,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      expect(error, findsOneWidget);
      api.participantsFail = false;
      await tapVisible(
        tester,
        find.widgetWithText(TextButton, 'Spróbuj ponownie'),
      );
      expect(find.text('Maria Nowak'), findsOneWidget);
      expect(find.text('Anna Klient (wybrany profil)'), findsOneWidget);
      expect(api.participantReads, greaterThan(1));
    },
  );

  testWidgets('agenda and pass confirmation fit a phone with enlarged text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await pump(
      tester,
      _Api(data: groupClassJson(passCount: 2)),
      phoneSize: true,
    );
    await tapVisible(tester, find.text('Pole dance'));
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Zapisz się'));
    expect(tester.takeException(), isNull);
    expect(find.text('Potwierdź zapis'), findsOneWidget);
    expect(
      find.widgetWithText(FilledButton, 'Zapisz się').last.hitTestable(),
      findsOneWidget,
    );
  });
}
