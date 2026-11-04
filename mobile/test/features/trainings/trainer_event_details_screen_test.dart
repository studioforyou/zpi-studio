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
  bool fails = false;
  int calls = 0;

  @override
  Future<List<EventParticipant>> fetch(String eventId) async {
    calls++;
    if (fails) {
      throw const ApiException(statusCode: 500, message: 'Server error');
    }
    return participants;
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
}) => EventParticipant(
  reservationId: 'reservation-$name',
  customerId: 'customer-$name',
  displayName: name,
  isChild: isChild,
  birthDate: birthDate,
  bookedAt: DateTime(2026, 10, 30, 12),
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
}
