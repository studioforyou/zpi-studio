import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/presentation/add_class_screen.dart';
import 'package:mobile/features/schedule/presentation/time_input.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _pole = PlanningServiceType(
  id: 'pole',
  name: 'Pole dance',
  defaultDurationMinutes: 75,
  maxParticipants: 10,
);

const _gym = PlanningRoom(id: 'gym', name: 'Gym', maxPeople: 12);

/// Returns [answer] for each preview and remembers the plans it got.
class _FakePlanningApi implements ClassPlanningApi {
  PlanPreview Function(ClassPlan plan) answer = _free;
  Object? previewError;
  Object? createError;
  final previews = <ClassPlan>[];
  final created = <ClassPlan>[];

  @override
  Future<List<PlanningServiceType>> fetchServiceTypes() async => [_pole];

  @override
  Future<List<PlanningRoom>> fetchRooms() async => [_gym];

  @override
  Future<PlanPreview> preview(ClassPlan plan) async {
    previews.add(plan);
    if (previewError case final error?) {
      throw error;
    }
    return answer(plan);
  }

  @override
  Future<void> create(ClassPlan plan) async {
    if (createError case final error?) {
      throw error;
    }
    created.add(plan);
  }
}

/// The occurrences of [plan] without problems, except those [blocked].
PlanPreview _free(ClassPlan plan, {Set<DateTime> blocked = const {}}) {
  final days = switch (plan) {
    OneOffClassPlan(:final startsAt) => [
      DateTime(startsAt.year, startsAt.month, startsAt.day),
    ],
    RecurringClassPlan(:final startsOn, :final endsOn, :final weekdays) => [
      for (
        var day = startsOn;
        !day.isAfter(endsOn);
        day = DateTime(day.year, day.month, day.day + 1)
      )
        if (weekdays.contains(day.weekday)) day,
    ],
  };
  final excluded = plan is RecurringClassPlan
      ? plan.excludedDates
      : const <DateTime>{};
  final occurrences = [
    for (final day in days)
      PlannedOccurrence(
        date: day,
        startsAt: DateTime(day.year, day.month, day.day, 18),
        endsAt: DateTime(day.year, day.month, day.day, 19, 15),
        excluded: excluded.contains(day),
        issues: [
          if (blocked.contains(day) && !excluded.contains(day))
            PlanIssue(
              type: IssueType.roomBusy,
              blocking: true,
              eventTitle: 'Calisthenics',
              startsAt: DateTime(day.year, day.month, day.day, 17, 30),
              endsAt: DateTime(day.year, day.month, day.day, 18, 30),
            ),
        ],
      ),
  ];
  return PlanPreview(
    occurrences: occurrences,
    warnings: const [],
    conflictCount: occurrences.where((o) => o.hasBlockingIssue).length,
  );
}

void main() {
  // A Tuesday far enough ahead.
  final day = DateTime(2030, 1, 8);

  Future<_FakePlanningApi> pumpScreen(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = _FakePlanningApi();
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => const Scaffold(body: Text('Schedule')),
          routes: [
            GoRoute(
              path: 'new',
              builder: (_, _) => AddClassScreen(initialDay: day),
            ),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [classPlanningApiProvider.overrideWithValue(api)],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
        ),
      ),
    );
    router.push('/new');
    await tester.pumpAndSettle();
    return api;
  }

  Future<void> choose(WidgetTester tester, String key, String option) async {
    await tester.tap(find.byKey(Key(key)));
    await tester.pumpAndSettle();
    await tester.tap(find.text(option).last);
    await tester.pumpAndSettle();
  }

  Future<void> waitForPreview(WidgetTester tester) async {
    await tester.pump(previewDelay);
    await tester.pumpAndSettle();
  }

  ButtonStyleButton saveButton(WidgetTester tester) =>
      tester.widget<ButtonStyleButton>(find.byKey(const Key('addClassSave')));

  Future<void> fillIn(WidgetTester tester) async {
    await choose(tester, 'addClassServiceType', 'Pole dance');
    await choose(tester, 'addClassRoom', 'Gym (do 12 os.)');
    await waitForPreview(tester);
  }

  testWidgets('adds a free one-off class with the service type duration', (
    tester,
  ) async {
    final api = await pumpScreen(tester);
    expect(
      find.text('Wybierz rodzaj zajęć i salę, aby sprawdzić termin.'),
      findsOneWidget,
    );
    expect(saveButton(tester).onPressed, isNull);

    await fillIn(tester);

    expect(find.text('Termin jest wolny.'), findsOneWidget);
    expect(find.text('Puste pole: limit rodzaju zajęć (10)'), findsOneWidget);
    final plan = api.previews.last as OneOffClassPlan;
    expect(plan.serviceTypeId, 'pole');
    expect(plan.roomId, 'gym');
    expect(plan.capacity, isNull);
    expect(plan.startsAt, DateTime(2030, 1, 8, 18));
    expect(plan.endsAt, DateTime(2030, 1, 8, 19, 15));

    await tester.tap(find.byKey(const Key('addClassSave')));
    await tester.pumpAndSettle();

    final saved = api.created.single as OneOffClassPlan;
    expect(saved.startsAt, plan.startsAt);
    expect(saved.endsAt, plan.endsAt);
    expect(find.byType(AddClassScreen), findsNothing);
    expect(find.text('Zajęcia dodane do grafiku.'), findsOneWidget);
  });

  testWidgets('a conflict is shown and blocks saving', (tester) async {
    final api = await pumpScreen(tester);
    api.answer = (plan) => _free(plan, blocked: {day});

    await fillIn(tester);

    expect(
      find.text('Ten termin koliduje z innymi zajęciami lub dostępnością.'),
      findsOneWidget,
    );
    expect(find.text('Sala zajęta: Calisthenics 17:30–18:30'), findsOneWidget);
    expect(find.text('Pomiń ten termin'), findsNothing);
    expect(saveButton(tester).onPressed, isNull);

    // A change of the plan checks it again.
    api.answer = _free;
    await tester.enterText(find.byKey(const Key('addClassDuration')), '60');
    expect(saveButton(tester).onPressed, isNull);
    await waitForPreview(tester);

    expect(find.text('Termin jest wolny.'), findsOneWidget);
    expect(
      (api.previews.last as OneOffClassPlan).endsAt,
      DateTime(2030, 1, 8, 19),
    );
    expect(saveButton(tester).onPressed, isNotNull);
  });

  testWidgets('a conflicting date can be left out of a recurring class', (
    tester,
  ) async {
    final api = await pumpScreen(tester);
    final busy = DateTime(2030, 1, 22);
    api.answer = (plan) => _free(plan, blocked: {busy});

    await tester.tap(find.text('Cykliczne'));
    await tester.pumpAndSettle();
    await fillIn(tester);

    final plan = api.previews.last as RecurringClassPlan;
    expect(plan.weekdays, {DateTime.tuesday});
    expect(plan.startsOn, day);
    expect(plan.endsOn, DateTime(2030, 2, 8));
    expect(
      find.text('Terminy z konfliktem: 1. Zmień termin albo pomiń te daty.'),
      findsOneWidget,
    );
    expect(saveButton(tester).onPressed, isNull);

    await tester.tap(find.text('Pomiń ten termin'));
    await waitForPreview(tester);

    expect((api.previews.last as RecurringClassPlan).excludedDates, {busy});
    expect(find.text('Wszystkie terminy są wolne (4).'), findsOneWidget);
    expect(find.text('Pominięte'), findsOneWidget);
    expect(find.text('Dodaj zajęcia (4)'), findsOneWidget);

    await tester.tap(find.byKey(const Key('addClassWeekday4')));
    await waitForPreview(tester);

    // Another plan starts without left-out dates.
    final changed = api.previews.last as RecurringClassPlan;
    expect(changed.weekdays, {DateTime.tuesday, DateTime.thursday});
    expect(changed.excludedDates, isEmpty);
  });

  testWidgets('explains a plan the studio does not allow', (tester) async {
    final api = await pumpScreen(tester);
    api.previewError = const PlanRejectedException(
      statusCode: 422,
      message: 'The service may not use this room.',
      code: 'SERVICE_NOT_ALLOWED_IN_ROOM',
    );

    await fillIn(tester);

    expect(
      find.text('Te zajęcia nie mogą odbywać się w wybranej sali.'),
      findsOneWidget,
    );
    expect(saveButton(tester).onPressed, isNull);

    api.previewError = null;
    await tester.tap(find.text('Spróbuj ponownie'));
    await waitForPreview(tester);
    expect(find.text('Termin jest wolny.'), findsOneWidget);
  });

  testWidgets('a time taken while saving shows the new conflicts', (
    tester,
  ) async {
    final api = await pumpScreen(tester);
    await fillIn(tester);

    api
      ..createError = const PlanRejectedException(
        statusCode: 409,
        message: 'Taken',
        code: 'RESOURCE_CAPACITY_EXCEEDED',
      )
      ..answer = (plan) => _free(plan, blocked: {day});
    await tester.tap(find.byKey(const Key('addClassSave')));
    await waitForPreview(tester);

    expect(api.created, isEmpty);
    expect(find.byType(AddClassScreen), findsOneWidget);
    expect(
      find.text(
        'Termin został w międzyczasie zajęty. Sprawdź konflikty poniżej.',
      ),
      findsOneWidget,
    );
    expect(find.text('Sala zajęta: Calisthenics 17:30–18:30'), findsOneWidget);
  });

  testWidgets('rejects an invalid capacity and duration', (tester) async {
    final api = await pumpScreen(tester);
    await fillIn(tester);
    final checked = api.previews.length;

    await tester.enterText(find.byKey(const Key('addClassCapacity')), '0');
    await waitForPreview(tester);
    expect(find.text('Podaj liczbę większą od zera.'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('addClassCapacity')), '8');
    await tester.enterText(find.byKey(const Key('addClassDuration')), '2');
    await waitForPreview(tester);
    expect(find.text('Podaj od 5 do 720 minut.'), findsOneWidget);
    expect(api.previews.length, checked);
    expect(saveButton(tester).onPressed, isNull);

    await tester.enterText(find.byKey(const Key('addClassDuration')), '50');
    await waitForPreview(tester);
    expect((api.previews.last as OneOffClassPlan).capacity, 8);
  });

  testWidgets('the start time is typed and must be a real time', (
    tester,
  ) async {
    final api = await pumpScreen(tester);
    await fillIn(tester);
    final field = find.byKey(const Key('addClassStartTime'));
    final checked = api.previews.length;

    await tester.enterText(field, '33:33');
    await waitForPreview(tester);
    expect(
      find.text('Podaj godzinę od 00:00 do 23:59, np. 18:30.'),
      findsOneWidget,
    );
    expect(api.previews.length, checked);
    expect(saveButton(tester).onPressed, isNull);

    await tester.enterText(field, '07:45');
    await waitForPreview(tester);
    expect(
      find.text('Podaj godzinę od 00:00 do 23:59, np. 18:30.'),
      findsNothing,
    );
    final plan = api.previews.last as OneOffClassPlan;
    expect(plan.startsAt, DateTime(2030, 1, 8, 7, 45));
    expect(saveButton(tester).onPressed, isNotNull);
  });

  test('parses only times from 00:00 to 23:59', () {
    expect(parseTime('18:30'), const TimeOfDay(hour: 18, minute: 30));
    expect(parseTime('7:05'), const TimeOfDay(hour: 7, minute: 5));
    expect(parseTime('00:00'), const TimeOfDay(hour: 0, minute: 0));
    expect(parseTime('23:59'), const TimeOfDay(hour: 23, minute: 59));
    for (final text in [
      '33:33',
      '24:00',
      '12:60',
      '1230',
      '12:3',
      '',
      'ab:cd',
    ]) {
      expect(parseTime(text), isNull, reason: text);
    }
  });

  test('the time field takes only digits and adds the colon', () {
    const formatter = TimeInputFormatter();
    TextEditingValue type(String old, String text) =>
        formatter.formatEditUpdate(
          TextEditingValue(text: old),
          TextEditingValue(text: text),
        );

    expect(type('1', '18').text, '18:');
    expect(type('18:', '18').text, '18');
    expect(type('18:3', '18:30').text, '18:30');
    expect(type('18:30', '18:305').text, '18:30');
    expect(type('18', '18a').text, '18');
    expect(type('9', '9:').text, '9:');
  });
}
