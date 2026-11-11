import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
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

/// The child profile of [_session] as a picker choice.
final _child = SessionChoice(
  role: AppRole.client,
  profile: _session.profiles[1],
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
  int classReads = 0;
  String? joinedPassId;
  ClassQuery? joinedQuery;

  /// The last place of the class frees up just before the join arrives.
  bool placeFreedBeforeJoin = false;
  String? leftEntryId;
  BookingScope? leftScope;
  String? confirmedEntryId;

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
    classReads++;
    if (fails) throw const ClassBookingException(statusCode: 503);
    return GroupClass.fromJson(data);
  }

  @override
  Future<ClassWaitlistEntry> joinWaitlist(
    ClassQuery query,
    String customerPassId,
  ) async {
    joinedPassId = customerPassId;
    joinedQuery = query;
    if (placeFreedBeforeJoin) {
      data = groupClassJson();
      throw const ClassBookingException(
        statusCode: 409,
        code: 'CLASS_NOT_FULL',
      );
    }
    data = fullClassJson(waitlistPosition: 3);
    return ClassWaitlistEntry.fromJson(waitlistEntryJson(position: 3));
  }

  @override
  Future<ClassWaitlistEntry> confirmWaitlistOffer(
    BookingScope scope,
    ClassWaitlistEntry entry,
  ) async {
    confirmedEntryId = entry.id;
    data = groupClassJson(reserved: true);
    return ClassWaitlistEntry.fromJson({
      ...waitlistEntryJson(waiting: false),
      'status': 'PROMOTED',
      'reservationId': 'reservation-1',
    });
  }

  @override
  Future<ClassWaitlistEntry> leaveWaitlist(
    BookingScope scope,
    ClassWaitlistEntry entry,
  ) async {
    leftScope = scope;
    leftEntryId = entry.id;
    data = fullClassJson(waitingCount: 2);
    return ClassWaitlistEntry.fromJson(waitlistEntryJson(waiting: false));
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

void main() {
  Future<ProviderContainer> pump(
    WidgetTester tester,
    _Api api, {
    bool details = false,
    bool reservations = false,
    bool phoneSize = false,
    List<ScheduleServiceType> serviceTypes = const [],
    List<StudioTrainer> trainers = const [],
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
        scheduleServiceTypesApiProvider.overrideWithValue(
          _ServiceTypesApi(serviceTypes),
        ),
        studioTrainersApiProvider.overrideWithValue(_TrainersApi(trainers)),
        // The changes banner reads ahead on its own; series_change_test covers it.
        scheduleChangesProvider.overrideWith((ref, owner) => Stream.value([])),
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
    await tester.tap(find.byTooltip('Zamknij filtry'));
    await tester.pumpAndSettle();
  }

  Finder inSheet(Finder finder) =>
      find.descendant(of: find.byType(BottomSheet), matching: finder);

  /// Opens the collapsible [section] of the filter sheet.
  Future<void> expandSection(WidgetTester tester, String section) async {
    await tapVisible(tester, inSheet(find.textContaining(section)).first);
  }

  /// Ticks [label] in the collapsible [section] of the filter sheet.
  Future<void> check(WidgetTester tester, String section, String label) async {
    await expandSection(tester, section);
    await tapVisible(tester, find.widgetWithText(CheckboxListTile, label));
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
      await check(tester, 'Rodzaj zajęć', 'Kalistenika');
      expect(inSheet(find.text('Rodzaj zajęć (1)')), findsOneWidget);
      await closeFilters(tester);
      expect(find.text('Pole dance'), findsNothing);
      expect(find.text('Trening siłowy'), findsOneWidget);
      await tapVisible(tester, find.text('Wyczyść'));
      await tapVisible(tester, find.text('Filtry'));
      await check(tester, 'Prowadzący', 'Anna Trener');
      await closeFilters(tester);
      expect(find.text('Pole dance'), findsOneWidget);
      expect(find.text('Trening siłowy'), findsNothing);
    },
  );

  testWidgets('level and age group filters use the service of each class', (
    tester,
  ) async {
    final api = _Api()
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
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Akrobatyka dla dzieci'), findsOneWidget);

    await tapVisible(tester, find.text('Filtry'));
    await check(tester, 'Poziom', 'Zaawansowany');
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsNothing);
    expect(find.text('Akrobatyka dla dzieci'), findsOneWidget);
    expect(find.text('Filtry (1)'), findsOneWidget);

    await tapVisible(tester, find.text('Wyczyść'));
    await tapVisible(tester, find.text('Filtry'));
    await tapVisible(tester, find.widgetWithText(FilterChip, 'Dorośli'));
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Akrobatyka dla dzieci'), findsNothing);
  });

  testWidgets(
    'offer filters list every offered class type, level and trainer',
    (tester) async {
      await pump(
        tester,
        _Api(),
        serviceTypes: const [
          ScheduleServiceType(
            id: 'service-1',
            name: 'Pole dance dla początkujących',
            category: 'GROUP_CLASS',
            level: 'BEGINNER',
            minAge: 16,
            maxAge: null,
          ),
          ScheduleServiceType(
            id: 'service-9',
            name: 'Akrobatyka',
            category: 'GROUP_CLASS',
            level: 'ADVANCED',
            minAge: 7,
            maxAge: 12,
          ),
          ScheduleServiceType(
            id: 'service-pt',
            name: 'Trening personalny',
            category: 'PERSONAL_TRAINING',
            level: 'INTERMEDIATE',
            minAge: null,
            maxAge: null,
          ),
          ScheduleServiceType(
            id: 'service-old',
            name: 'Joga',
            category: 'GROUP_CLASS',
            level: 'ALL_LEVELS',
            minAge: null,
            maxAge: null,
            isActive: false,
          ),
        ],
        trainers: const [
          StudioTrainer(staffId: 'staff-1', displayName: 'Anna Trener'),
          StudioTrainer(staffId: 'staff-9', displayName: 'Zofia Nowak'),
        ],
      );
      await tapVisible(tester, find.text('Filtry'));
      // Lists start collapsed, so long ones do not crowd the sheet.
      expect(find.byType(CheckboxListTile), findsNothing);

      await expandSection(tester, 'Rodzaj zajęć');
      await expandSection(tester, 'Poziom');
      await expandSection(tester, 'Prowadzący');
      for (final label in [
        'Akrobatyka',
        'Pole dance dla początkujących',
        'Początkujący',
        'Zaawansowany',
        'Anna Trener',
        'Zofia Nowak',
      ]) {
        expect(find.widgetWithText(CheckboxListTile, label), findsOneWidget);
      }
      // Personal training and inactive services are not in the offer.
      for (final label in [
        'Trening personalny',
        'Joga',
        'Średniozaawansowany',
        'Wszystkie poziomy',
      ]) {
        expect(find.widgetWithText(CheckboxListTile, label), findsNothing);
      }
      expect(find.widgetWithText(FilterChip, 'Dzieci'), findsOneWidget);
      expect(find.widgetWithText(FilterChip, 'Dorośli'), findsOneWidget);

      // A trainer without classes in the period can still be chosen.
      await tapVisible(
        tester,
        find.widgetWithText(CheckboxListTile, 'Zofia Nowak'),
      );
      await closeFilters(tester);
      expect(find.text('Pole dance'), findsNothing);
      expect(find.text('Filtry (1)'), findsOneWidget);
    },
  );

  testWidgets('the filter sheet has a fixed height and a close button', (
    tester,
  ) async {
    await pump(tester, _Api());
    await tapVisible(tester, find.text('Filtry'));
    final sheet = tester.getSize(inSheet(find.byType(ListView)).first);
    final screen = tester.view.physicalSize / tester.view.devicePixelRatio;
    expect(sheet.height, lessThan(screen.height * .8));
    expect(find.byTooltip('Zamknij filtry'), findsOneWidget);
    await closeFilters(tester);
    expect(find.byType(BottomSheet), findsNothing);
  });

  testWidgets('own reservations have no filters', (tester) async {
    await pump(
      tester,
      _Api(data: groupClassJson(reserved: true)),
      reservations: true,
    );
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Filtry'), findsNothing);
  });

  testWidgets('level and age group choices are hidden without service types', (
    tester,
  ) async {
    await pump(tester, _Api());
    await tapVisible(tester, find.text('Filtry'));
    expect(find.text('Poziom'), findsNothing);
    expect(find.text('Grupa wiekowa'), findsNothing);
    await closeFilters(tester);
    expect(find.text('Pole dance'), findsOneWidget);
  });

  testWidgets(
    'selected filter names remain recognizable in a period without those classes',
    (tester) async {
      final api = _Api();
      await pump(tester, api);
      await tapVisible(tester, find.text('Filtry'));
      await check(tester, 'Rodzaj zajęć', 'Pole dance dla początkujących');
      await check(tester, 'Prowadzący', 'Anna Trener');
      await closeFilters(tester);
      expect(find.text('Filtry (2)'), findsOneWidget);

      await tapVisible(tester, find.byTooltip('Następny'));
      expect(find.text('Pole dance'), findsNothing);
      await tapVisible(tester, find.text('Filtry (2)'));
      await expandSection(tester, 'Rodzaj zajęć');
      await expandSection(tester, 'Prowadzący');
      for (final label in ['Pole dance dla początkujących', 'Anna Trener']) {
        final choice = find.widgetWithText(CheckboxListTile, label);
        expect(choice, findsOneWidget);
        expect(tester.widget<CheckboxListTile>(choice).value, isTrue);
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
      find.text(
        'Przed zapisem zaakceptuj wymagane dokumenty w sekcji Dokumenty i zgody.',
      ),
      findsOneWidget,
    );
    expect(
      find.widgetWithText(OutlinedButton, 'Przejdź do dokumentów'),
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
      container.read(authControllerProvider.notifier).choose(_child);
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

  Finder joinButton() =>
      find.widgetWithText(FilledButton, 'Dołącz do listy rezerwowej');
  Finder leaveButton() =>
      find.widgetWithText(OutlinedButton, 'Opuść listę rezerwową');
  VoidCallback? onPressed(WidgetTester tester, Finder button) =>
      tester.widget<ButtonStyleButton>(button).onPressed;

  testWidgets(
    'full class joins the waiting list with an explicitly chosen pass',
    (tester) async {
      final api = _Api(data: fullClassJson(waitingCount: 2, passCount: 2));
      await pump(tester, api, details: true);
      expect(find.text('Brak wolnych miejsc.'), findsOneWidget);
      expect(
        find.text(
          'Dołącz do listy rezerwowej — gdy zwolni się miejsce, '
          'zapiszemy Cię automatycznie.',
        ),
        findsOneWidget,
      );
      expect(
        find.text('Na liście rezerwowej czekają już 2 osoby.'),
        findsOneWidget,
      );
      expect(find.widgetWithText(FilledButton, 'Zapisz się'), findsNothing);

      await tapVisible(tester, joinButton());
      expect(find.text('Dołączyć do listy rezerwowej?'), findsOneWidget);
      expect(find.text('Uczestnik: Anna Klient'), findsNWidgets(2));
      expect(
        find.text(
          'Gdy zwolni się miejsce, zapiszemy Cię automatycznie. '
          'Obowiązują wtedy zwykłe zasady odwołania.',
        ),
        findsOneWidget,
      );
      expect(
        find.text(
          'Jeśli wybranego karnetu nie będzie można wtedy użyć, '
          'skorzystamy z innego ważnego karnetu.',
        ),
        findsOneWidget,
      );
      final confirm = find.widgetWithText(FilledButton, 'Dołącz');
      expect(onPressed(tester, confirm), isNull);
      for (final pass in ['Karnet 1', 'Karnet 2']) {
        await tester.tap(find.byType(DropdownButtonFormField<String>));
        await tester.pumpAndSettle();
        await tester.tap(find.textContaining(pass).last);
        await tester.pumpAndSettle();
        // Only a pass with limited entries is charged an entry.
        expect(
          find.text('Z karnetu pobierzemy wtedy 1 wejście.'),
          pass == 'Karnet 1' ? findsOneWidget : findsNothing,
        );
      }
      await tester.tap(confirm);
      await tester.pumpAndSettle();

      expect(api.joinedPassId, 'pass-2');
      expect(api.joinedQuery, (scope: _scope, eventId: 'class-1'));
      expect(api.bookedPassId, isNull);
      expect(find.text('Dołączono do listy rezerwowej.'), findsOneWidget);
      expect(find.text('Jesteś na liście rezerwowej'), findsOneWidget);
      expect(find.text('Pozycja na liście: 3 z 3'), findsOneWidget);
      expect(
        find.text(
          'Gdy zwolni się miejsce, zapiszemy Cię automatycznie — '
          'rezerwacja pojawi się w zakładce Mój grafik.',
        ),
        findsOneWidget,
      );
      expect(joinButton(), findsNothing);
      expect(onPressed(tester, leaveButton()), isNotNull);
    },
  );

  testWidgets('waiting participant leaves the list only after confirming', (
    tester,
  ) async {
    final api = _Api(data: fullClassJson(waitlistPosition: 2, waitingCount: 3));
    await pump(tester, api, details: true);
    expect(find.text('Jesteś na liście rezerwowej'), findsOneWidget);
    expect(find.text('Pozycja na liście: 2 z 3'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Zapisz się'), findsNothing);

    await tapVisible(tester, leaveButton());
    expect(find.text('Opuścić listę rezerwową?'), findsOneWidget);
    expect(find.text('Stracisz swoje miejsce w kolejce.'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Zostań na liście'));
    await tester.pumpAndSettle();
    expect(api.leftEntryId, isNull);
    expect(find.text('Pozycja na liście: 2 z 3'), findsOneWidget);

    await tapVisible(tester, leaveButton());
    await tester.tap(find.widgetWithText(FilledButton, 'Opuść listę'));
    await tester.pumpAndSettle();
    expect(api.leftEntryId, 'waitlist-1');
    expect(api.leftScope, _scope);
    expect(find.text('Opuszczono listę rezerwową.'), findsOneWidget);
    expect(find.text('Jesteś na liście rezerwowej'), findsNothing);
    expect(onPressed(tester, joinButton()), isNotNull);
  });

  for (final (name, data) in [
    (
      'a disabled',
      fullClassJson(
        waitlistAllowed: false,
        waitlistReason: 'WAITLIST_DISABLED',
      ),
    ),
    ('no', fullClassJson()..remove('waitlist')),
  ]) {
    testWidgets('full class with $name waiting list keeps disabled booking', (
      tester,
    ) async {
      await pump(tester, _Api(data: data), details: true);
      expect(find.text('Brak wolnych miejsc'), findsOneWidget);
      expect(
        onPressed(tester, find.widgetWithText(FilledButton, 'Zapisz się')),
        isNull,
      );
      expect(find.text('Dołącz do listy rezerwowej'), findsNothing);
    });
  }

  for (final (reason, passCount, text) in [
    (
      'NO_ELIGIBLE_PASS',
      0,
      'Wybrany uczestnik nie ma karnetu ważnego na te zajęcia, '
          'z dostępnym wejściem i odpowiednim zakresem usług.',
    ),
    (
      'REQUIRED_DOCUMENTS_MISSING',
      1,
      'Przed zapisem zaakceptuj wymagane dokumenty w sekcji '
          'Dokumenty i zgody.',
    ),
  ]) {
    testWidgets('full class explains why joining is refused: $reason', (
      tester,
    ) async {
      await pump(
        tester,
        _Api(
          data: fullClassJson(
            waitlistAllowed: false,
            waitlistReason: reason,
            waitingCount: 2,
            passCount: passCount,
          ),
        ),
        details: true,
      );
      expect(find.text('Brak wolnych miejsc.'), findsOneWidget);
      expect(find.text(text), findsOneWidget);
      expect(find.textContaining('zapiszemy Cię automatycznie'), findsNothing);
      expect(
        find.widgetWithText(OutlinedButton, 'Przejdź do dokumentów'),
        reason == 'REQUIRED_DOCUMENTS_MISSING' ? findsOneWidget : findsNothing,
      );
      expect(onPressed(tester, joinButton()), isNull);
      expect(find.widgetWithText(FilledButton, 'Zapisz się'), findsNothing);
    });
  }

  for (final (reason, text) in [
    (
      'REQUIRED_DOCUMENTS_MISSING',
      'Na razie nie zapiszemy Cię automatycznie. Przed zapisem zaakceptuj '
          'wymagane dokumenty w sekcji Dokumenty i zgody.',
    ),
    (
      'NO_ELIGIBLE_PASS',
      'Na razie nie zapiszemy Cię automatycznie. Wybrany uczestnik nie ma '
          'karnetu ważnego na te zajęcia, z dostępnym wejściem i odpowiednim '
          'zakresem usług.',
    ),
    (
      'BOOKING_CLOSED',
      'Lista rezerwowa jest już zamknięta — nie przydzielimy z niej miejsca '
          'na te zajęcia.',
    ),
  ]) {
    testWidgets('waiting participant sees a blocked promotion: $reason', (
      tester,
    ) async {
      await pump(
        tester,
        _Api(
          data: fullClassJson(
            waitlistPosition: 1,
            promotionBlockedReason: reason,
          ),
        ),
        details: true,
      );
      expect(find.text('Pozycja na liście: 1 z 1'), findsOneWidget);
      expect(find.text(text), findsOneWidget);
      expect(find.textContaining('rezerwacja pojawi się'), findsNothing);
      expect(
        find.widgetWithText(OutlinedButton, 'Przejdź do dokumentów'),
        reason == 'REQUIRED_DOCUMENTS_MISSING' ? findsOneWidget : findsNothing,
      );
      expect(onPressed(tester, leaveButton()), isNotNull);
    });
  }

  testWidgets('waiting participant books a place the queue left free', (
    tester,
  ) async {
    final api = _Api(
      data: groupClassJson(availablePlaces: 1, waitlistPosition: 1),
    );
    await pump(tester, api, details: true);
    expect(find.text('Jesteś na liście rezerwowej'), findsOneWidget);
    expect(
      find.text('Zwolniło się miejsce — możesz zapisać się na zajęcia.'),
      findsOneWidget,
    );
    expect(find.textContaining('rezerwacja pojawi się'), findsNothing);
    expect(onPressed(tester, leaveButton()), isNotNull);
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Zapisz się'));
    expect(find.text('Potwierdź zapis'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Zapisz się').last);
    await tester.pumpAndSettle();
    expect(api.bookedPassId, 'pass-1');
    expect(api.joinedPassId, isNull);
    expect(find.text('Masz rezerwację'), findsOneWidget);
    expect(leaveButton(), findsNothing);
  });

  testWidgets('a place freed while joining is offered for booking instead', (
    tester,
  ) async {
    final api = _Api(data: fullClassJson())..placeFreedBeforeJoin = true;
    await pump(tester, api, details: true);
    final reads = api.classReads;
    await tapVisible(tester, joinButton());
    // A single eligible pass needs no choice.
    await tester.tap(find.widgetWithText(FilledButton, 'Dołącz'));
    await tester.pumpAndSettle();
    expect(api.joinedPassId, 'pass-1');
    expect(api.classReads, greaterThan(reads));
    expect(
      find.text('Zwolniło się miejsce — możesz zapisać się na zajęcia.'),
      findsOneWidget,
    );
    expect(find.text('Dołączono do listy rezerwowej.'), findsNothing);
    expect(joinButton(), findsNothing);
    expect(
      onPressed(tester, find.widgetWithText(FilledButton, 'Zapisz się')),
      isNotNull,
    );
  });

  testWidgets(
    'changing profile while joining never adds old participant to the list',
    (tester) async {
      final api = _Api(data: fullClassJson());
      final container = await pump(tester, api, details: true);
      await tapVisible(tester, joinButton());
      container.read(authControllerProvider.notifier).choose(_child);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Dołącz'));
      await tester.pumpAndSettle();
      expect(api.joinedPassId, isNull);
      expect(
        find.textContaining('Zmieniono profil uczestnika'),
        findsOneWidget,
      );
    },
  );

  testWidgets('calendar marks the waiting position of an upcoming class', (
    tester,
  ) async {
    await pump(
      tester,
      _Api(data: fullClassJson(waitlistPosition: 2, waitingCount: 3)),
    );
    expect(find.text('Lista rezerwowa · 2'), findsOneWidget);
    expect(find.byIcon(Icons.hourglass_top_outlined), findsOneWidget);
    expect(find.text('Masz rezerwację'), findsNothing);
    await tester.tap(find.text('Dzień'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.hourglass_top), findsOneWidget);
  });

  testWidgets('late parent response does not replace selected child offer', (
    tester,
  ) async {
    final pending = Completer<List<GroupClass>>();
    final api = _Api()..pendingSelf = pending;
    final container = await pump(tester, api);
    container.read(authControllerProvider.notifier).choose(_child);
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
    'a place booked without a pass is cancelled without an entry consequence',
    (tester) async {
      final api = _Api(
        data: groupClassJson(reserved: true, reservedWithPass: false),
      );
      await pump(tester, api, details: true);
      expect(
        find.text('Studio zapisało to miejsce bez karnetu.'),
        findsOneWidget,
      );
      expect(find.textContaining('bez zwrotu wejścia'), findsNothing);
      expect(find.textContaining('Bezpłatne odwołanie'), findsNothing);
      await tapVisible(
        tester,
        find.widgetWithText(OutlinedButton, 'Odwołaj rezerwację'),
      );
      expect(
        find.text(
          'Rezerwacja jest bez karnetu, więc odwołanie nie zmienia wejść.',
        ),
        findsOneWidget,
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Odwołaj rezerwację'));
      await tester.pumpAndSettle();
      expect(api.cancellations, 1);
      expect(api.expectedEntryReturned, isNull);
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

  testWidgets('waiting list confirmation fits a phone with enlarged text', (
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
      _Api(data: fullClassJson(waitingCount: 12, passCount: 2)),
      details: true,
      phoneSize: true,
    );
    final waitingCount = find.text('Na liście rezerwowej czeka już 12 osób.');
    await tester.scrollUntilVisible(
      waitingCount,
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(waitingCount, findsOneWidget);
    await tapVisible(tester, joinButton());
    expect(tester.takeException(), isNull);
    expect(find.text('Dołączyć do listy rezerwowej?'), findsOneWidget);
    expect(
      find.widgetWithText(FilledButton, 'Dołącz').hitTestable(),
      findsOneWidget,
    );
  });

  testWidgets('an offered place is confirmed before its deadline', (
    tester,
  ) async {
    final api = _Api(data: offeredClassJson());
    await pump(tester, api, details: true);
    expect(find.text('Zwolniło się miejsce dla Ciebie'), findsOneWidget);
    expect(find.textContaining('Potwierdź je do'), findsOneWidget);
    expect(find.textContaining('Pozycja na liście'), findsNothing);
    expect(joinButton(), findsNothing);
    await tapVisible(
      tester,
      find.widgetWithText(FilledButton, 'Potwierdź miejsce'),
    );
    expect(find.text('Potwierdzić miejsce?'), findsOneWidget);
    await tester.tap(
      find.widgetWithText(FilledButton, 'Potwierdź miejsce').last,
    );
    await tester.pumpAndSettle();
    expect(api.confirmedEntryId, 'waitlist-1');
    expect(find.text('Masz rezerwację'), findsOneWidget);
    expect(
      find.text('Miejsce potwierdzone — masz rezerwację.'),
      findsOneWidget,
    );
  });

  testWidgets('declining an offered place passes it to the next person', (
    tester,
  ) async {
    final api = _Api(data: offeredClassJson());
    await pump(tester, api, details: true);
    await tapVisible(
      tester,
      find.widgetWithText(OutlinedButton, 'Zrezygnuj z miejsca'),
    );
    expect(
      find.text('Miejsce przejdzie na kolejną osobę z listy rezerwowej.'),
      findsOneWidget,
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Zrezygnuj z miejsca'));
    await tester.pumpAndSettle();
    expect(api.leftEntryId, 'waitlist-1');
    expect(api.confirmedEntryId, isNull);
    expect(find.text('Zrezygnowano z miejsca.'), findsOneWidget);
  });

  testWidgets('an expired offer cannot be confirmed', (tester) async {
    await pump(
      tester,
      _Api(data: offeredClassJson(offerExpiresAt: '2020-01-01T00:00:00Z')),
      details: true,
    );
    expect(
      find.text(
        'Czas na potwierdzenie miejsca minął — przeszło ono na kolejną osobę z listy.',
      ),
      findsOneWidget,
    );
    expect(
      onPressed(tester, find.widgetWithText(FilledButton, 'Potwierdź miejsce')),
      isNull,
    );
  });

  testWidgets('an offer blocked by missing documents points to them', (
    tester,
  ) async {
    await pump(
      tester,
      _Api(
        data: offeredClassJson(
          promotionBlockedReason: 'REQUIRED_DOCUMENTS_MISSING',
        ),
      ),
      details: true,
    );
    expect(
      find.textContaining('Teraz nie możemy Cię zapisać.'),
      findsOneWidget,
    );
    expect(
      find.widgetWithText(OutlinedButton, 'Przejdź do dokumentów'),
      findsOneWidget,
    );
    expect(
      onPressed(tester, find.widgetWithText(FilledButton, 'Potwierdź miejsce')),
      isNull,
    );
  });

  testWidgets(
    'joining a class with a confirmation time says the place is held',
    (tester) async {
      await pump(
        tester,
        _Api(data: withConfirmation(fullClassJson())),
        details: true,
      );
      expect(find.textContaining('na 30 min do potwierdzenia'), findsOneWidget);
    },
  );

  testWidgets('waiting for a class with a confirmation time explains it', (
    tester,
  ) async {
    await pump(
      tester,
      _Api(data: withConfirmation(fullClassJson(waitlistPosition: 2))),
      details: true,
    );
    expect(
      find.textContaining('potwierdź je wtedy na tym ekranie'),
      findsOneWidget,
    );
  });
}
