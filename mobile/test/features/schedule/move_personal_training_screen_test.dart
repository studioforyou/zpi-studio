import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/personal_training_change_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
import 'package:mobile/features/trainings/presentation/trainer_event_details_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'trainer-account',
  displayName: 'Marek Nowak',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [],
  activeProfileId: null,
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
}

final _tomorrow = DateTime.now().add(const Duration(days: 1));
final _startsAt = DateTime(_tomorrow.year, _tomorrow.month, _tomorrow.day, 10);

ScheduleEvent _event({
  EventKind kind = EventKind.personalTraining,
  bool lead = true,
  DateTime? startsAt,
  EventStatus status = EventStatus.scheduled,
}) {
  final start = startsAt ?? _startsAt;
  return ScheduleEvent(
    id: 'event-1',
    kind: kind,
    status: status,
    title: 'Trening personalny',
    serviceTypeName: 'Trening personalny',
    startsAt: start,
    endsAt: start.add(const Duration(hours: 1)),
    capacity: 1,
    staff: [
      EventStaff(staffId: 'staff-1', displayName: 'Marek Nowak', isLead: lead),
    ],
    roomNames: const ['Siłownia'],
    roomIds: const ['gym'],
    eventVersion: 2,
  );
}

class _ScheduleApi implements ScheduleApi {
  _ScheduleApi(this.event);

  ScheduleEvent event;

  @override
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async => [
    event,
  ];

  @override
  Future<ScheduleEvent> fetchOwnEvent(String eventId) async => event;
}

class _ParticipantsApi implements EventParticipantsApi {
  @override
  Future<EventAttendance> fetchAttendance(String eventId) async =>
      EventAttendance(
        eventStatus: EventStatus.scheduled,
        markingOpensAt: DateTime(2100),
        markingClosesAt: DateTime(2100, 1, 2),
        markingOpen: false,
        participants: const [],
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _ChangeApi implements PersonalTrainingChangeApi {
  _ChangeApi(this.schedule);

  final _ScheduleApi schedule;
  final previews = <PersonalTrainingMove>[];
  final changes = <PersonalTrainingMove>[];
  bool busy = false;
  String? rejection;

  @override
  Future<PlanPreview> preview(String eventId, PersonalTrainingMove move) async {
    previews.add(move);
    return PlanPreview(
      conflictCount: busy ? 1 : 0,
      warnings: const [],
      occurrences: [
        PlannedOccurrence(
          date: move.startsAt,
          startsAt: move.startsAt,
          endsAt: move.endsAt,
          excluded: false,
          issues: [
            if (busy) const PlanIssue(type: IssueType.roomBusy, blocking: true),
          ],
        ),
      ],
    );
  }

  @override
  Future<ScheduleEvent> change(
    String eventId,
    PersonalTrainingMove move,
  ) async {
    if (rejection case final code?) {
      throw PlanRejectedException(code: code, message: code, statusCode: 409);
    }
    changes.add(move);
    schedule.event = _event(startsAt: move.startsAt);
    return schedule.event;
  }
}

void main() {
  late _ScheduleApi scheduleApi;
  late _ChangeApi changeApi;

  Future<void> pump(WidgetTester tester, ScheduleEvent event) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    scheduleApi = _ScheduleApi(event);
    changeApi = _ChangeApi(scheduleApi);
    await tester.pumpWidget(
      ProviderScope(
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(_Auth.new),
          scheduleApiProvider.overrideWithValue(scheduleApi),
          eventParticipantsApiProvider.overrideWithValue(_ParticipantsApi()),
          personalTrainingChangeApiProvider.overrideWithValue(changeApi),
          planningRoomsProvider.overrideWith(
            (_) async => const [
              PlanningRoom(id: 'gym', name: 'Siłownia', maxPeople: 10),
              PlanningRoom(id: 'pole', name: 'Sala pole dance', maxPeople: 8),
            ],
          ),
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

  Future<void> openMove(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('personalMoveButton')));
    await tester.pumpAndSettle();
    // The current time is checked once the form opens.
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
  }

  FilledButton saveButton(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const Key('personalMoveSave')));

  testWidgets('only the lead trainer can move an upcoming personal training', (
    tester,
  ) async {
    await pump(tester, _event());
    expect(find.text('Zmień termin'), findsOneWidget);

    await pump(tester, _event(lead: false));
    expect(find.byKey(const Key('personalMoveButton')), findsNothing);

    await pump(tester, _event(kind: EventKind.groupClass));
    expect(find.byKey(const Key('personalMoveButton')), findsNothing);

    await pump(tester, _event(status: EventStatus.cancelled));
    expect(find.byKey(const Key('personalMoveButton')), findsNothing);

    await pump(
      tester,
      _event(startsAt: DateTime.now().subtract(const Duration(hours: 2))),
    );
    expect(find.byKey(const Key('personalMoveButton')), findsNothing);
  });

  testWidgets('the form starts at the current date and cannot save it as is', (
    tester,
  ) async {
    await pump(tester, _event());
    await openMove(tester);

    expect(find.text('Zmień termin treningu'), findsOneWidget);
    expect(
      find.text(
        'Klient dostanie powiadomienie o nowym terminie. '
        'Nie musi go akceptować.',
      ),
      findsOneWidget,
    );
    expect(find.widgetWithText(TextField, '10:00'), findsOneWidget);
    expect(find.widgetWithText(TextField, '60'), findsOneWidget);
    expect(changeApi.previews.single.startsAt, _startsAt);
    expect(find.text('Termin jest wolny.'), findsOneWidget);
    expect(saveButton(tester).onPressed, isNull);
  });

  testWidgets('a new time and room are checked, saved and shown', (
    tester,
  ) async {
    await pump(tester, _event());
    await openMove(tester);

    await tester.enterText(find.byKey(const Key('personalMoveTime')), '16:30');
    await tester.tap(find.byKey(const Key('personalMoveRoom')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sala pole dance').last);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    final checked = changeApi.previews.last;
    expect(checked.roomId, 'pole');
    expect(checked.startsAt, _startsAt.add(const Duration(minutes: 390)));
    expect(checked.toJson(), {
      'version': 2,
      'startsAt': checked.startsAt.toUtc().toIso8601String(),
      'endsAt': checked.endsAt.toUtc().toIso8601String(),
      'resources': [
        {'resourceId': 'pole', 'blocksWholeRoom': true},
      ],
    });

    await tester.tap(find.byKey(const Key('personalMoveSave')));
    await tester.pumpAndSettle();

    expect(changeApi.changes.single.startsAt, checked.startsAt);
    expect(find.text('Zapisano nowy termin treningu.'), findsOneWidget);
    expect(find.text('16:30–17:30'), findsOneWidget);
  });

  testWidgets('a conflicting time cannot be saved', (tester) async {
    await pump(tester, _event());
    await openMove(tester);
    changeApi.busy = true;

    await tester.enterText(find.byKey(const Key('personalMoveTime')), '12:00');
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    expect(saveButton(tester).onPressed, isNull);
    expect(changeApi.changes, isEmpty);
  });

  testWidgets('a training changed meanwhile asks to open it again', (
    tester,
  ) async {
    await pump(tester, _event());
    await openMove(tester);
    changeApi.rejection = 'VERSION_CONFLICT';

    await tester.enterText(find.byKey(const Key('personalMoveTime')), '12:00');
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('personalMoveSave')));
    await tester.pumpAndSettle();

    expect(
      find.textContaining('Trening zmienił się w międzyczasie'),
      findsOneWidget,
    );
    expect(changeApi.changes, isEmpty);
  });
}
