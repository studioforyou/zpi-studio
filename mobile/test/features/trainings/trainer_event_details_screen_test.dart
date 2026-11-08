import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
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
  bool fails = false;

  @override
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async => [
    event,
  ];

  @override
  Future<ScheduleEvent> fetchOwnEvent(String eventId) async {
    if (fails) {
      throw const ApiException(statusCode: 404, message: 'Not found');
    }
    return event;
  }
}

class _FakeParticipantsApi implements EventParticipantsApi {
  _FakeParticipantsApi(this.participants);

  List<EventParticipant> participants;
  EventStatus status = EventStatus.scheduled;

  /// The trainer's time: before it by default, so attendance cannot be marked.
  DateTime opensAt = DateTime(2100);
  DateTime closesAt = DateTime(2100, 1, 2);
  bool markingOpen = false;

  bool fails = false;
  AttendanceException? rejection;
  int calls = 0;
  final marks = <Map<String, Attendance>>[];
  int completions = 0;

  EventAttendance get _sheet => EventAttendance(
    eventStatus: status,
    markingOpensAt: opensAt,
    markingClosesAt: closesAt,
    markingOpen: markingOpen,
    participants: participants,
  );

  /// Opens the trainer's time, as after the start of the class.
  void open() {
    opensAt = DateTime.now().subtract(const Duration(hours: 1));
    closesAt = DateTime(2100);
    markingOpen = true;
  }

  @override
  Future<EventAttendance> fetchAttendance(String eventId) async {
    calls++;
    if (fails) {
      throw const AttendanceException(statusCode: 500);
    }
    return _sheet;
  }

  @override
  Future<EventAttendance> mark(
    String eventId,
    Map<String, Attendance> marks,
  ) async {
    if (rejection case final rejection?) throw rejection;
    this.marks.add(marks);
    participants = [
      for (final participant in participants)
        _participant(
          participant.displayName,
          attendance:
              marks[participant.reservationId] ?? participant.attendance,
        ),
    ];
    return _sheet;
  }

  @override
  Future<EventAttendance> complete(String eventId) async {
    completions++;
    status = EventStatus.completed;
    participants = [
      for (final participant in participants)
        _participant(
          participant.displayName,
          attendance: participant.attendance == Attendance.unmarked
              ? Attendance.present
              : participant.attendance,
        ),
    ];
    return _sheet;
  }
}

final _startsAt = DateTime(2026, 11, 3, 18);

ScheduleEvent _event({
  EventStatus status = EventStatus.scheduled,
  int? capacity = 10,
}) => ScheduleEvent(
  id: 'event-1',
  kind: EventKind.groupClass,
  status: status,
  title: 'Pole dance dla dzieci',
  serviceTypeName: 'Pole dance',
  startsAt: _startsAt,
  endsAt: _startsAt.add(const Duration(hours: 1)),
  capacity: capacity,
  staff: const [
    EventStaff(staffId: 'staff-1', displayName: 'Anna Kowalska', isLead: true),
    EventStaff(staffId: 'staff-2', displayName: 'Marek Nowak', isLead: false),
  ],
  roomNames: const ['Sala pole dance'],
);

EventParticipant _participant(
  String name, {
  bool isChild = false,
  DateTime? birthDate,
  Attendance attendance = Attendance.unmarked,
}) => EventParticipant(
  reservationId: 'reservation-$name',
  customerId: 'customer-$name',
  displayName: name,
  isChild: isChild,
  birthDate: birthDate,
  bookedAt: DateTime(2026, 10, 30, 12),
  attendance: attendance,
);

void main() {
  late _FakeScheduleApi scheduleApi;
  late _FakeParticipantsApi participantsApi;

  setUp(() {
    scheduleApi = _FakeScheduleApi(_event());
    participantsApi = _FakeParticipantsApi([]);
  });

  Future<void> pumpScreen(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        // Failures stay visible instead of being retried in the background.
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(_SignedInAuthController.new),
          scheduleApiProvider.overrideWithValue(scheduleApi),
          eventParticipantsApiProvider.overrideWithValue(participantsApi),
        ],
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: TrainerEventDetailsScreen(eventId: 'event-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('shows the class, its staff and room', (tester) async {
    await pumpScreen(tester);

    expect(find.text('18:00–19:00'), findsOneWidget);
    expect(find.text('Pole dance dla dzieci'), findsOneWidget);
    expect(find.text('Zajęcia grupowe · Pole dance'), findsOneWidget);
    expect(find.text('wtorek 3 listopada'), findsOneWidget);
    expect(find.text('Prowadzący: Anna Kowalska'), findsOneWidget);
    expect(find.text('Asystent: Marek Nowak'), findsOneWidget);
    expect(find.text('Sala: Sala pole dance'), findsOneWidget);
  });

  testWidgets('lists participants with their age on the day of the class', (
    tester,
  ) async {
    participantsApi.participants = [
      // 10 on the day of the class: the birthday is on that very day.
      _participant(
        'Zosia Ćwik',
        isChild: true,
        birthDate: DateTime(2016, 11, 3),
      ),
      // Still 21: the birthday comes the next day.
      _participant('Ola Nowak', birthDate: DateTime(2004, 11, 4)),
      _participant('Piotr Żak'),
    ];

    await pumpScreen(tester);

    expect(find.text('Uczestnicy (3)'), findsOneWidget);
    expect(find.text('3/10 zajętych'), findsOneWidget);
    expect(find.text('Zosia Ćwik'), findsOneWidget);
    expect(find.text('Dziecko · 10 lat'), findsOneWidget);
    expect(find.text('21 lat'), findsOneWidget);
    expect(find.text('Piotr Żak'), findsOneWidget);
    expect(find.text('ZĆ'), findsOneWidget);
    final names = tester
        .widgetList<ListTile>(find.byType(ListTile))
        .map((tile) => (tile.title! as Text).data)
        .toList();
    expect(names, ['Zosia Ćwik', 'Ola Nowak', 'Piotr Żak']);
  });

  testWidgets('a class without bookings says so', (tester) async {
    scheduleApi.event = _event(capacity: null);

    await pumpScreen(tester);

    expect(find.text('Uczestnicy (0)'), findsOneWidget);
    expect(find.text('Bez limitu miejsc'), findsOneWidget);
    expect(
      find.text('Nikt jeszcze nie zapisał się na te zajęcia.'),
      findsOneWidget,
    );
  });

  testWidgets('a cancelled class is marked', (tester) async {
    scheduleApi.event = _event(status: EventStatus.cancelled);

    await pumpScreen(tester);

    expect(find.text('Odwołane'), findsOneWidget);
  });

  testWidgets('a failed participant list can be loaded again', (tester) async {
    participantsApi.fails = true;
    await pumpScreen(tester);

    expect(
      find.text('Nie udało się pobrać listy uczestników.'),
      findsOneWidget,
    );

    participantsApi
      ..fails = false
      ..participants = [_participant('Ola Nowak')];
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();

    expect(find.text('Ola Nowak'), findsOneWidget);
    expect(participantsApi.calls, 2);
  });

  testWidgets('an event that cannot be loaded offers a retry', (tester) async {
    scheduleApi.fails = true;
    await pumpScreen(tester);

    expect(
      find.text('Nie udało się pobrać zajęć. Spróbuj ponownie.'),
      findsOne,
    );

    scheduleApi.fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();

    expect(find.text('Pole dance dla dzieci'), findsOneWidget);
  });

  testWidgets('the trainer records attendance once the class has started', (
    tester,
  ) async {
    participantsApi
      ..participants = [_participant('Ola Nowak'), _participant('Piotr Żak')]
      ..open();
    await pumpScreen(tester);

    expect(
      find.textContaining('Obecność możesz oznaczać i zmieniać do'),
      findsOne,
    );
    expect(find.text('Obecni: 0 · Nieobecni: 0 · Bez oznaczenia: 2'), findsOne);

    await tester.tap(find.byIcon(Icons.check).first);
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.close).last);
    await tester.pumpAndSettle();

    expect(participantsApi.marks, [
      {'reservation-Ola Nowak': Attendance.present},
      {'reservation-Piotr Żak': Attendance.absent},
    ]);
    expect(find.text('Obecni: 1 · Nieobecni: 1 · Bez oznaczenia: 0'), findsOne);
  });

  testWidgets('marking the class as held records the unmarked as present', (
    tester,
  ) async {
    participantsApi
      ..participants = [
        _participant('Ola Nowak', attendance: Attendance.absent),
        _participant('Piotr Żak'),
      ]
      ..open();
    await pumpScreen(tester);

    await tester.tap(find.text('Oznacz zajęcia jako odbyte'));
    await tester.pumpAndSettle();

    expect(
      find.text(
        '1 osoba bez oznaczonej obecności zostanie oznaczona jako obecna.',
      ),
      findsOne,
    );
    await tester.tap(find.text('Oznacz jako odbyte'));
    await tester.pumpAndSettle();

    expect(participantsApi.completions, 1);
    expect(find.text('Zajęcia oznaczone jako odbyte.'), findsOne);
    expect(find.text('Obecni: 1 · Nieobecni: 1 · Bez oznaczenia: 0'), findsOne);
    expect(find.text('Oznacz zajęcia jako odbyte'), findsNothing);
  });

  testWidgets('dismissing the confirmation keeps the class unchanged', (
    tester,
  ) async {
    participantsApi
      ..participants = [_participant('Piotr Żak')]
      ..open();
    await pumpScreen(tester);

    await tester.tap(find.text('Oznacz zajęcia jako odbyte'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Wróć'));
    await tester.pumpAndSettle();

    expect(participantsApi.completions, 0);
    expect(find.text('Oznacz zajęcia jako odbyte'), findsOne);
  });

  testWidgets('after the trainer\'s time attendance can only be read', (
    tester,
  ) async {
    participantsApi
      ..participants = [
        _participant('Ola Nowak', attendance: Attendance.present),
        _participant('Piotr Żak'),
      ]
      ..opensAt = DateTime(2026, 11, 3, 18)
      ..closesAt = DateTime(2026, 11, 4, 7);
    await pumpScreen(tester);

    expect(
      find.text(
        'Czas na oznaczenie obecności minął. Korekty wprowadza administrator.',
      ),
      findsOne,
    );
    expect(find.byType(SegmentedButton<Attendance>), findsOne);
    await tester.tap(find.byIcon(Icons.close));
    await tester.pumpAndSettle();
    expect(participantsApi.marks, isEmpty);
    expect(find.text('Oznacz zajęcia jako odbyte'), findsNothing);
  });

  testWidgets('a rejected mark is explained and the sheet loaded again', (
    tester,
  ) async {
    participantsApi
      ..participants = [_participant('Ola Nowak')]
      ..open()
      ..rejection = const AttendanceException(
        statusCode: 422,
        code: 'ATTENDANCE_CLOSED',
      );
    await pumpScreen(tester);
    expect(participantsApi.calls, 1);

    await tester.tap(find.byIcon(Icons.check));
    await tester.pumpAndSettle();

    expect(
      find.text(
        'Czas na oznaczenie obecności minął. Korekty wprowadza administrator.',
      ),
      findsOne,
    );
    expect(participantsApi.calls, 2);
  });
}
