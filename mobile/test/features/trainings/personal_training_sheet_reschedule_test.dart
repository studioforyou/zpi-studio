import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/reschedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/reschedule_event_screen.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
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

ScheduleEvent _event({bool lead = true, DateTime? startsAt}) {
  final start = startsAt ?? _startsAt;
  return ScheduleEvent(
    id: 'event-1',
    kind: EventKind.personalTraining,
    status: EventStatus.scheduled,
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

  final ScheduleEvent event;
  bool fail = false;

  @override
  Future<ScheduleEvent> fetchOwnEvent(String eventId) async {
    if (fail) throw Exception('offline');
    return event;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _RescheduleApi implements RescheduleApi {
  final saved = <ReschedulePlan>[];

  @override
  Future<PlanPreview> preview(String eventId, ReschedulePlan plan) async =>
      PlanPreview(
        conflictCount: 0,
        warnings: const [],
        occurrences: [
          PlannedOccurrence(
            date: plan.startsAt,
            startsAt: plan.startsAt,
            endsAt: plan.endsAt,
            excluded: false,
            issues: const [],
          ),
        ],
      );

  @override
  Future<ScheduleEvent> reschedule(String eventId, ReschedulePlan plan) async {
    saved.add(plan);
    return _event(startsAt: plan.startsAt);
  }
}

void main() {
  late _ScheduleApi scheduleApi;

  Future<void> openSheet(WidgetTester tester, ScheduleEvent event) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    scheduleApi = _ScheduleApi(event);
    final training = TraineeTraining(
      event: event,
      reservationId: 'reservation-1',
      trainerConfirmedAt: null,
      clientConfirmedAt: null,
      attendance: 'UNMARKED',
      settlementStatus: 'OPEN',
      funding: 'RESERVED',
      passId: 'pass-1',
      passName: 'Pakiet 10',
      requiresAttention: false,
      customerName: 'Monika Dąbrowska',
    );
    await tester.pumpWidget(
      ProviderScope(
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(_Auth.new),
          scheduleApiProvider.overrideWithValue(scheduleApi),
          rescheduleApiProvider.overrideWithValue(_RescheduleApi()),
          planningRoomsProvider.overrideWith(
            (_) async => const [
              PlanningRoom(id: 'gym', name: 'Siłownia', maxPeople: 10),
            ],
          ),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          home: Scaffold(
            body: PersonalTrainingScheduleTile(
              training: training,
              checkedAt: DateTime.now(),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Monika Dąbrowska'));
    await tester.pumpAndSettle();
  }

  const button = Key('traineeTrainingReschedule');

  testWidgets('the lead trainer opens the reschedule form from the sheet', (
    tester,
  ) async {
    await openSheet(tester, _event());
    await tester.tap(find.byKey(button));
    await tester.pumpAndSettle();

    expect(find.byType(RescheduleEventScreen), findsOneWidget);
    expect(find.byKey(button), findsNothing);
  });

  testWidgets('an assistant gets no reschedule button in the sheet', (
    tester,
  ) async {
    await openSheet(tester, _event(lead: false));

    expect(find.byKey(button), findsNothing);
  });

  testWidgets('a failed load keeps the sheet open with an error', (
    tester,
  ) async {
    await openSheet(tester, _event());
    scheduleApi.fail = true;
    await tester.tap(find.byKey(button));
    await tester.pumpAndSettle();

    expect(find.byType(RescheduleEventScreen), findsNothing);
    expect(
      find.text('Nie udało się pobrać zajęć. Spróbuj ponownie.'),
      findsOneWidget,
    );
  });
}
