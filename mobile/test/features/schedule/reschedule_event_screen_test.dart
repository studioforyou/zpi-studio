import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/reschedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/reschedule_event_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

final _startsAt = DateTime(2027, 1, 12, 18);

ScheduleEvent _event({
  DateTime? startsAt,
  EventStatus status = EventStatus.scheduled,
  EventKind kind = EventKind.groupClass,
  bool lead = true,
}) {
  final start = startsAt ?? _startsAt;
  return ScheduleEvent(
    id: 'event-1',
    kind: kind,
    status: status,
    title: 'Pole dance',
    serviceTypeName: 'Pole dance',
    startsAt: start,
    endsAt: start.add(const Duration(hours: 1)),
    capacity: 10,
    staff: [
      EventStaff(
        staffId: 'staff-1',
        displayName: 'Anna Kowalska',
        isLead: lead,
      ),
    ],
    roomNames: const ['Sala pole dance'],
    roomIds: const ['pole'],
    eventVersion: 4,
  );
}

PlanPreview _preview(ReschedulePlan plan, {bool busy = false}) => PlanPreview(
  conflictCount: busy ? 1 : 0,
  warnings: const [],
  occurrences: [
    PlannedOccurrence(
      date: plan.startsAt,
      startsAt: plan.startsAt,
      endsAt: plan.endsAt,
      excluded: false,
      issues: [
        if (busy) const PlanIssue(type: IssueType.roomBusy, blocking: true),
      ],
    ),
  ],
);

class _Api implements RescheduleApi {
  final previews = <ReschedulePlan>[];
  final saved = <ReschedulePlan>[];
  bool busy = false;
  PlanRejectedException? rejection;

  @override
  Future<PlanPreview> preview(String eventId, ReschedulePlan plan) async {
    previews.add(plan);
    return _preview(plan, busy: busy);
  }

  @override
  Future<ScheduleEvent> reschedule(String eventId, ReschedulePlan plan) async {
    if (rejection case final rejection?) throw rejection;
    saved.add(plan);
    return _event(startsAt: plan.startsAt);
  }
}

void main() {
  late _Api api;
  ScheduleEvent? result;

  Future<void> pump(WidgetTester tester, {int? participants = 6}) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    api = _Api();
    result = null;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          rescheduleApiProvider.overrideWithValue(api),
          planningRoomsProvider.overrideWith(
            (_) async => const [
              PlanningRoom(id: 'gym', name: 'Sala fitness', maxPeople: 12),
              PlanningRoom(id: 'pole', name: 'Sala pole dance', maxPeople: 10),
            ],
          ),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  result = await Navigator.of(context).push<ScheduleEvent>(
                    MaterialPageRoute(
                      builder: (_) => RescheduleEventScreen(
                        event: _event(),
                        participantCount: participants,
                      ),
                    ),
                  );
                },
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  Future<void> enterTime(WidgetTester tester, String time) async {
    await tester.enterText(find.byKey(const Key('rescheduleTime')), time);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
  }

  FilledButton saveButton(WidgetTester tester) => tester.widget<FilledButton>(
    find.descendant(
      of: find.byKey(const Key('rescheduleSave')),
      matching: find.byType(FilledButton),
      matchRoot: true,
    ),
  );

  group('canReschedule', () {
    final now = DateTime(2027, 1, 1);

    test('the lead of a future scheduled class can move it', () {
      expect(canReschedule(_event(), 'staff-1', now: now), isTrue);
      expect(
        canReschedule(
          _event(kind: EventKind.personalTraining),
          'staff-1',
          now: now,
        ),
        isTrue,
      );
    });

    test('an assistant, another trainer or a past event cannot', () {
      expect(canReschedule(_event(lead: false), 'staff-1', now: now), isFalse);
      expect(canReschedule(_event(), 'staff-2', now: now), isFalse);
      expect(canReschedule(_event(), null, now: now), isFalse);
      expect(
        canReschedule(_event(), 'staff-1', now: DateTime(2027, 1, 13)),
        isFalse,
      );
      expect(
        canReschedule(
          _event(status: EventStatus.cancelled),
          'staff-1',
          now: now,
        ),
        isFalse,
      );
      expect(
        canReschedule(
          _event(kind: EventKind.consultation),
          'staff-1',
          now: now,
        ),
        isFalse,
      );
    });
  });

  testWidgets('starts with the current time and room and cannot save them', (
    tester,
  ) async {
    await pump(tester);

    expect(find.text('wtorek, 12 stycznia 2027'), findsOneWidget);
    expect(find.text('18:00'), findsOneWidget);
    expect(find.text('60'), findsOneWidget);
    expect(find.text('Sala pole dance'), findsOneWidget);
    expect(find.text('To jest obecny termin zajęć.'), findsOneWidget);
    expect(saveButton(tester).onPressed, isNull);
    expect(api.previews, isEmpty);
  });

  testWidgets('checks a new time and saves it after confirmation', (
    tester,
  ) async {
    await pump(tester);

    await tester.enterText(find.byKey(const Key('rescheduleDuration')), '90');
    await enterTime(tester, '19:00');

    final checked = api.previews.last;
    expect(checked.version, 4);
    expect(checked.startsAt, DateTime(2027, 1, 12, 19));
    expect(checked.endsAt, DateTime(2027, 1, 12, 20, 30));
    expect(checked.roomId, isNull);
    expect(find.text('Termin jest wolny.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('rescheduleSave')));
    await tester.pumpAndSettle();
    expect(find.text('Zmienić termin?'), findsOneWidget);
    expect(
      find.text('6 uczestników dostanie powiadomienie o zmianie.'),
      findsOneWidget,
    );
    expect(
      find.textContaining('12.01, 19:00–20:30, Sala pole dance'),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('rescheduleConfirm')));
    await tester.pumpAndSettle();
    expect(api.saved.single.startsAt, DateTime(2027, 1, 12, 19));
    expect(result?.startsAt, DateTime(2027, 1, 12, 19));
    expect(find.text('open'), findsOneWidget);
  });

  testWidgets('dismissing the confirmation saves nothing', (tester) async {
    await pump(tester, participants: null);
    await enterTime(tester, '17:00');

    await tester.tap(find.byKey(const Key('rescheduleSave')));
    await tester.pumpAndSettle();
    expect(
      find.text('Uczestnicy dostaną powiadomienie o zmianie.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Anuluj'));
    await tester.pumpAndSettle();

    expect(api.saved, isEmpty);
    expect(find.text('Zmień termin'), findsOneWidget);
  });

  testWidgets('another room is sent and a conflict blocks saving', (
    tester,
  ) async {
    await pump(tester);
    api.busy = true;

    await tester.tap(find.byKey(const Key('rescheduleRoom')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sala fitness').last);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pumpAndSettle();

    expect(api.previews.last.roomId, 'gym');
    expect(api.previews.last.startsAt, _startsAt);
    expect(saveButton(tester).onPressed, isNull);
  });

  testWidgets('a change made meanwhile asks to reload the event', (
    tester,
  ) async {
    await pump(tester);
    await enterTime(tester, '19:00');
    api.rejection = const PlanRejectedException(
      code: 'VERSION_CONFLICT',
      message: 'Modified',
      statusCode: 409,
    );
    final checks = api.previews.length;

    await tester.tap(find.byKey(const Key('rescheduleSave')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('rescheduleConfirm')));
    await tester.pumpAndSettle();

    expect(
      find.text(
        'Zajęcia zostały w międzyczasie zmienione. Odśwież szczegóły i '
        'spróbuj ponownie.',
      ),
      findsOneWidget,
    );
    expect(api.previews.length, checks);
    expect(saveButton(tester).onPressed, isNull);
    expect(result, isNull);
  });
}
