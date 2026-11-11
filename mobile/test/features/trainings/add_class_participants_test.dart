import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/class_booking_api.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/presentation/add_class_participants_screen.dart';
import 'package:mobile/features/trainings/presentation/trainer_event_details_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'trainer-account',
  displayName: 'Anna Kowalska',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [],
  activeProfileId: null,
);

class _SignedInAuthController extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
}

class _FakeScheduleApi implements ScheduleApi {
  _FakeScheduleApi(this.event);

  ScheduleEvent event;

  @override
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async => [
    event,
  ];

  @override
  Future<ScheduleEvent> fetchOwnEvent(String eventId) async => event;
}

class _FakeParticipantsApi implements EventParticipantsApi {
  List<EventParticipant> participants = [];
  int calls = 0;

  @override
  Future<EventAttendance> fetchAttendance(String eventId) async {
    calls++;
    return EventAttendance(
      eventStatus: EventStatus.scheduled,
      markingOpensAt: DateTime(2100),
      markingClosesAt: DateTime(2100, 1, 2),
      markingOpen: false,
      participants: participants,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeTraineesApi implements TraineesApi {
  _FakeTraineesApi(this.customers);

  final List<CustomerSummary> customers;

  @override
  Future<List<CustomerSummary>> searchCustomers(String query) async => [
    for (final customer in customers)
      if (customer.displayName.toLowerCase().contains(query.toLowerCase()))
        customer,
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeClassBookingApi implements ClassBookingApi {
  final options = <String, BookingOptions>{};
  final bookings =
      <({String eventId, String customerId, String passId, String? reason})>[];
  final optionCalls = <String>[];
  ClassBookingException? rejection;

  /// Called after a booking, e.g. to put the client on the class.
  void Function(String customerId)? onBooked;

  @override
  Future<BookingOptions> fetchOptions(String eventId, String customerId) async {
    optionCalls.add(customerId);
    return options[customerId]!;
  }

  @override
  Future<void> book(
    String eventId, {
    required String customerId,
    required String passId,
    String? reason,
  }) async {
    if (rejection case final rejection?) {
      this.rejection = null;
      throw rejection;
    }
    bookings.add((
      eventId: eventId,
      customerId: customerId,
      passId: passId,
      reason: reason,
    ));
    onBooked?.call(customerId);
  }
}

ScheduleEvent _event({
  EventKind kind = EventKind.groupClass,
  EventStatus status = EventStatus.scheduled,
  DateTime? startsAt,
}) {
  final start = startsAt ?? DateTime.now().add(const Duration(days: 2));
  return ScheduleEvent(
    id: 'event-1',
    kind: kind,
    status: status,
    title: 'Pole dance',
    serviceTypeName: 'Pole dance',
    startsAt: start,
    endsAt: start.add(const Duration(hours: 1)),
    capacity: 10,
    staff: const [
      EventStaff(
        staffId: 'staff-1',
        displayName: 'Anna Kowalska',
        isLead: true,
      ),
    ],
    roomNames: const ['Sala 1'],
  );
}

EventParticipant _participant(String id, String name) => EventParticipant(
  reservationId: 'reservation-$id',
  customerId: id,
  displayName: name,
  isChild: false,
  birthDate: null,
  bookedAt: DateTime(2026, 11, 1),
);

const _pass = EligiblePass(
  id: 'pass-1',
  productName: 'Karnet 8 wejść',
  entriesRemaining: 5,
  validUntil: null,
);

BookingOptions _options({
  bool allowed = true,
  String? reason,
  List<EligiblePass> passes = const [_pass],
  String? waitlistStatus,
}) => BookingOptions(
  allowed: allowed,
  reason: reason,
  waitlistStatus: waitlistStatus,
  eligiblePasses: allowed ? passes : const [],
);

const _ola = CustomerSummary(
  id: 'ola',
  displayName: 'Ola Nowak',
  isChild: false,
  birthDate: null,
);
const _jan = CustomerSummary(
  id: 'jan',
  displayName: 'Jan Adamski',
  isChild: false,
  birthDate: null,
);
const _ewa = CustomerSummary(
  id: 'ewa',
  displayName: 'Ewa Lis',
  isChild: false,
  birthDate: null,
);

void main() {
  late _FakeScheduleApi scheduleApi;
  late _FakeParticipantsApi participantsApi;
  late _FakeClassBookingApi bookingApi;

  setUp(() {
    scheduleApi = _FakeScheduleApi(_event());
    participantsApi = _FakeParticipantsApi();
    bookingApi = _FakeClassBookingApi()
      ..onBooked = (id) => participantsApi.participants = [
        ...participantsApi.participants,
        _participant(id, id),
      ];
  });

  Future<void> pump(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(_SignedInAuthController.new),
          scheduleApiProvider.overrideWithValue(scheduleApi),
          eventParticipantsApiProvider.overrideWithValue(participantsApi),
          traineesApiProvider.overrideWithValue(
            _FakeTraineesApi([_jan, _ewa, _ola]),
          ),
          classBookingApiProvider.overrideWithValue(bookingApi),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          home: home,
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> pumpAddScreen(WidgetTester tester) => pump(
    tester,
    const AddClassParticipantsScreen(
      query: (accountId: 'trainer-account', eventId: 'event-1'),
    ),
  );

  final addButton = find.byTooltip('Dodaj klienta');

  group('the plus button in the class details', () {
    testWidgets('opens the client search for an upcoming group class', (
      tester,
    ) async {
      await pump(tester, const TrainerEventDetailsScreen(eventId: 'event-1'));
      expect(addButton, findsOneWidget);

      await tester.tap(addButton);
      await tester.pumpAndSettle();

      expect(find.text('Dodaj klientów do zajęć'), findsOneWidget);
      expect(find.text('Ola Nowak'), findsOneWidget);
    });

    for (final (description, event) in [
      ('a class that has started', _event(startsAt: DateTime(2026, 1, 1))),
      ('a cancelled class', _event(status: EventStatus.cancelled)),
      ('a held class', _event(status: EventStatus.completed)),
      ('a personal session', _event(kind: EventKind.personalTraining)),
    ]) {
      testWidgets('is not shown for $description', (tester) async {
        scheduleApi.event = event;
        await pump(tester, const TrainerEventDetailsScreen(eventId: 'event-1'));

        expect(find.text('Uczestnicy (0)'), findsOneWidget);
        expect(addButton, findsNothing);
      });
    }

    testWidgets('a booked client shows up among the participants', (
      tester,
    ) async {
      bookingApi.options['ola'] = _options();
      await pump(tester, const TrainerEventDetailsScreen(eventId: 'event-1'));
      await tester.tap(addButton);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ola Nowak'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Zapisz na zajęcia'));
      await tester.pumpAndSettle();
      Navigator.of(
        tester.element(find.byType(AddClassParticipantsScreen)),
      ).pop();
      await tester.pumpAndSettle();

      expect(find.text('Uczestnicy (1)'), findsOneWidget);
    });
  });

  testWidgets('clients already on the class cannot be added again', (
    tester,
  ) async {
    participantsApi.participants = [_participant('jan', 'Jan Adamski')];
    await pumpAddScreen(tester);

    final booked = tester.widget<ListTile>(
      find.widgetWithText(ListTile, 'Jan Adamski'),
    );
    expect(booked.enabled, isFalse);
    expect(find.text('Już zapisany'), findsOneWidget);

    await tester.tap(find.text('Jan Adamski'));
    await tester.pumpAndSettle();
    expect(bookingApi.optionCalls, isEmpty);
  });

  testWidgets('the search narrows the clients', (tester) async {
    await pumpAddScreen(tester);
    expect(find.byType(ListTile), findsNWidgets(3));

    await tester.enterText(find.byType(TextField), 'ewa');
    await tester.pump(AddClassParticipantsScreen.searchDelay);
    await tester.pumpAndSettle();

    expect(find.byType(ListTile), findsOneWidget);
    expect(find.text('Ewa Lis'), findsOneWidget);
  });

  testWidgets('several clients are booked one after another, '
      'a single pass chosen for the trainer', (tester) async {
    bookingApi.options['ola'] = _options(
      passes: [
        EligiblePass(
          id: 'pass-ola',
          productName: 'Karnet 8 wejść',
          entriesRemaining: 5,
          validUntil: DateTime(2026, 12, 31),
        ),
      ],
      waitlistStatus: 'WAITING',
    );
    bookingApi.options['ewa'] = _options();
    await pumpAddScreen(tester);

    await tester.tap(find.text('Ola Nowak'));
    await tester.pumpAndSettle();
    expect(find.text('Karnet 8 wejść · 5 wejść'), findsOneWidget);
    expect(find.text('ważny do 31.12.2026'), findsOneWidget);
    expect(
      find.text(
        'Klient czeka na liście rezerwowej. Zapis usunie go z kolejki.',
      ),
      findsOneWidget,
    );
    await tester.enterText(
      find.widgetWithText(TextField, 'Notatka (opcjonalnie)'),
      '  Zgłosiła się po zajęciach ',
    );
    await tester.tap(find.text('Zapisz na zajęcia'));
    await tester.pumpAndSettle();

    expect(find.text('Zapisano na zajęcia: Ola Nowak.'), findsOneWidget);
    expect(find.text('Już zapisany'), findsOneWidget);

    await tester.tap(find.text('Ewa Lis'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Zapisz na zajęcia'));
    await tester.pumpAndSettle();

    expect(bookingApi.bookings, hasLength(2));
    expect(bookingApi.bookings.first.customerId, 'ola');
    expect(bookingApi.bookings.first.passId, 'pass-ola');
    expect(bookingApi.bookings.first.reason, '  Zgłosiła się po zajęciach ');
    expect(bookingApi.bookings.last.customerId, 'ewa');
    expect(bookingApi.bookings.last.passId, 'pass-1');
    expect(find.text('Już zapisany'), findsNWidgets(2));
  });

  testWidgets('one of several passes has to be chosen', (tester) async {
    bookingApi.options['ola'] = _options(
      passes: const [
        EligiblePass(
          id: 'pass-1',
          productName: 'Karnet 4 wejścia',
          entriesRemaining: 1,
          validUntil: null,
        ),
        EligiblePass(
          id: 'pass-2',
          productName: 'Karnet open',
          entriesRemaining: null,
          validUntil: null,
        ),
      ],
    );
    await pumpAddScreen(tester);
    await tester.tap(find.text('Ola Nowak'));
    await tester.pumpAndSettle();

    final submit = find.widgetWithText(FilledButton, 'Zapisz na zajęcia');
    expect(tester.widget<FilledButton>(submit).onPressed, isNull);

    await tester.tap(find.text('Karnet open'));
    await tester.pumpAndSettle();
    expect(find.text('Karnet 4 wejścia · 1 wejście'), findsOneWidget);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(bookingApi.bookings.single.passId, 'pass-2');
  });

  for (final (reason, message) in [
    (
      'NO_ELIGIBLE_PASS',
      'Klient nie ma karnetu, który obejmuje te zajęcia. '
          'Zapis wymaga ważnego karnetu.',
    ),
    (
      'CLASS_FULL',
      'Zajęcia są pełne. '
          'Klient może dołączyć do listy rezerwowej w aplikacji.',
    ),
    ('BOOKING_CLOSED', 'Zapisy na te zajęcia są już zamknięte.'),
    ('BOOKING_NOT_OPEN', 'Zapisy na te zajęcia jeszcze się nie rozpoczęły.'),
    (
      'AGE_RESTRICTION',
      'Wiek klienta (albo brak daty urodzenia) '
          'nie mieści się w limicie wieku zajęć.',
    ),
    (
      'REQUIRED_DOCUMENTS_MISSING',
      'Klient nie zaakceptował dokumentów '
          'wymaganych na te zajęcia.',
    ),
    ('ALREADY_RESERVED', 'Ten klient jest już zapisany na te zajęcia.'),
    ('CUSTOMER_NOT_ACTIVE', 'Profil klienta jest nieaktywny.'),
    ('SOMETHING_NEW', 'Tego klienta nie można teraz zapisać na zajęcia.'),
  ]) {
    testWidgets('a client who cannot book themselves ($reason) cannot be '
        'booked by the trainer either', (tester) async {
      bookingApi.options['ola'] = _options(allowed: false, reason: reason);
      await pumpAddScreen(tester);
      await tester.tap(find.text('Ola Nowak'));
      await tester.pumpAndSettle();

      expect(find.text(message), findsOneWidget);
      expect(find.text('Zapisz na zajęcia'), findsNothing);
    });
  }

  testWidgets('a rule broken meanwhile is explained by the reloaded '
      'conditions', (tester) async {
    bookingApi
      ..options['ola'] = _options()
      ..rejection = const ClassBookingException(
        statusCode: 409,
        code: 'CLASS_FULL',
      );
    await pumpAddScreen(tester);
    await tester.tap(find.text('Ola Nowak'));
    await tester.pumpAndSettle();

    // Meanwhile the class filled up.
    bookingApi.options['ola'] = _options(allowed: false, reason: 'CLASS_FULL');
    await tester.tap(find.text('Zapisz na zajęcia'));
    await tester.pumpAndSettle();

    expect(bookingApi.optionCalls, ['ola', 'ola']);
    expect(
      find.text(
        'Zajęcia są pełne. '
        'Klient może dołączyć do listy rezerwowej w aplikacji.',
      ),
      findsOneWidget,
    );
    expect(find.text('Zapisz na zajęcia'), findsNothing);
    expect(bookingApi.bookings, isEmpty);
  });

  testWidgets('a pass that cannot fund the class any more is explained and '
      'chosen again', (tester) async {
    bookingApi
      ..options['ola'] = _options()
      ..rejection = const ClassBookingException(
        statusCode: 422,
        code: 'PASS_NOT_USABLE',
      );
    await pumpAddScreen(tester);
    await tester.tap(find.text('Ola Nowak'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Zapisz na zajęcia'));
    await tester.pumpAndSettle();

    expect(
      find.text('Wybranym karnetem nie można opłacić tych zajęć.'),
      findsOneWidget,
    );
    expect(bookingApi.optionCalls, ['ola', 'ola']);

    await tester.tap(find.text('Zapisz na zajęcia'));
    await tester.pumpAndSettle();
    expect(bookingApi.bookings.single.passId, 'pass-1');
  });
}
