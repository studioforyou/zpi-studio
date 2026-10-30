import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_screen.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
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
  _FakeScheduleApi(this.events);

  final List<ScheduleEvent> events;
  final windows = <ScheduleWindow>[];
  bool fails = false;

  @override
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async {
    windows.add(window);
    if (fails) {
      throw const ApiException(statusCode: 500, message: 'Server error');
    }
    return [
      for (final event in events)
        if (event.startsAt.isBefore(window.to) &&
            event.endsAt.isAfter(window.from))
          event,
    ];
  }
}

DateTime _today() {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day);
}

DateTime _monday(DateTime day) =>
    DateTime(day.year, day.month, day.day - (day.weekday - 1));

ScheduleEvent _event(
  String title,
  EventKind kind,
  DateTime startsAt, {
  EventStatus status = EventStatus.scheduled,
  bool isLead = true,
  String room = 'Pole room',
}) {
  return ScheduleEvent(
    id: title,
    kind: kind,
    status: status,
    title: title,
    serviceTypeName: null,
    startsAt: startsAt,
    endsAt: startsAt.add(const Duration(hours: 1)),
    capacity: 10,
    staff: [
      EventStaff(
        staffId: 'staff-1',
        displayName: 'Anna Kowalska',
        isLead: isLead,
      ),
    ],
    roomNames: [room],
  );
}

void main() {
  final today = _today();
  final tomorrow = DateTime(today.year, today.month, today.day + 1);

  Future<_FakeScheduleApi> pumpScreen(
    WidgetTester tester,
    List<ScheduleEvent> events,
  ) async {
    // Tall enough for a whole week of events.
    tester.view.physicalSize = const Size(800, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = _FakeScheduleApi(events);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(_SignedInAuthController.new),
          scheduleApiProvider.overrideWithValue(api),
        ],
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: TrainerScheduleScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return api;
  }

  testWidgets('opens on the current week with cancelled events marked', (
    tester,
  ) async {
    final api = await pumpScreen(tester, [
      _event(
        'Pole dance',
        EventKind.groupClass,
        DateTime(today.year, today.month, today.day, 18),
      ),
      _event(
        'PT with Tomasz',
        EventKind.personalTraining,
        DateTime(today.year, today.month, today.day, 8),
        status: EventStatus.cancelled,
      ),
    ]);

    final monday = _monday(today);
    expect(api.windows.single, (
      from: monday,
      to: DateTime(monday.year, monday.month, monday.day + 7),
    ));
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('PT with Tomasz'), findsOneWidget);
    expect(find.text('Odwołane'), findsWidgets);
    expect(find.text('18:00–19:00'), findsOneWidget);
    expect(find.text('Zajęcia grupowe · Pole room'), findsOneWidget);
  });

  testWidgets('the day view shows one day and moves between days', (
    tester,
  ) async {
    final api = await pumpScreen(tester, [
      _event(
        'Pole dance',
        EventKind.groupClass,
        DateTime(today.year, today.month, today.day, 18),
      ),
    ]);

    await tester.tap(find.text('Dzień'));
    await tester.pumpAndSettle();
    expect(api.windows.last, (from: today, to: tomorrow));
    expect(find.text('Pole dance'), findsOneWidget);

    await tester.tap(find.byTooltip('Następny'));
    await tester.pumpAndSettle();
    expect(api.windows.last.from, tomorrow);
    expect(find.text('Pole dance'), findsNothing);
    expect(find.text('Brak zajęć'), findsOneWidget);

    await tester.tap(find.text('Dziś'));
    await tester.pumpAndSettle();
    expect(find.text('Pole dance'), findsOneWidget);
  });

  testWidgets('the month view shows six weeks and the events of a tapped day', (
    tester,
  ) async {
    // The 15th and the 16th appear once in a six-week grid.
    final other = DateTime(today.year, today.month, today.day == 15 ? 16 : 15);
    final api = await pumpScreen(tester, [
      _event(
        'Pole dance',
        EventKind.groupClass,
        DateTime(today.year, today.month, today.day, 18),
      ),
      _event(
        'Workshop',
        EventKind.workshop,
        DateTime(other.year, other.month, other.day, 10),
      ),
    ]);

    await tester.tap(find.text('Miesiąc'));
    await tester.pumpAndSettle();
    final start = _monday(DateTime(today.year, today.month));
    expect(api.windows.last, (
      from: start,
      to: DateTime(start.year, start.month, start.day + 42),
    ));
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('Workshop'), findsNothing);

    await tester.tap(find.text('${other.day}'));
    await tester.pumpAndSettle();
    expect(find.text('Workshop'), findsOneWidget);
    expect(find.text('Pole dance'), findsNothing);
  });

  testWidgets('a failed fetch offers retry', (tester) async {
    final api = _FakeScheduleApi([
      _event(
        'Pole dance',
        EventKind.groupClass,
        DateTime(today.year, today.month, today.day, 18),
      ),
    ])..fails = true;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(_SignedInAuthController.new),
          scheduleApiProvider.overrideWithValue(api),
        ],
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: TrainerScheduleScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Nie udało się pobrać grafiku.'), findsOneWidget);

    api.fails = false;
    await tester.tap(find.text('Spróbuj ponownie'));
    await tester.pumpAndSettle();
    expect(find.text('Pole dance'), findsOneWidget);
  });

  testWidgets('filters by kind, role, room and cancelled events', (
    tester,
  ) async {
    await pumpScreen(tester, [
      _event(
        'Pole dance',
        EventKind.groupClass,
        DateTime(today.year, today.month, today.day, 18),
      ),
      _event(
        'Assisted workshop',
        EventKind.workshop,
        DateTime(today.year, today.month, today.day, 12),
        isLead: false,
        room: 'Gym',
      ),
      _event(
        'PT with Tomasz',
        EventKind.personalTraining,
        DateTime(today.year, today.month, today.day, 8),
        status: EventStatus.cancelled,
        room: 'Gym',
      ),
    ]);
    Future<void> tapInSheet(String text) async {
      await tester.tap(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text(text),
        ),
      );
      await tester.pumpAndSettle();
    }

    await tester.tap(find.text('Filtry'));
    await tester.pumpAndSettle();

    await tapInSheet('Warsztaty');
    expect(find.text('Assisted workshop'), findsOneWidget);
    expect(find.text('Pole dance'), findsNothing);
    await tapInSheet('Warsztaty');

    await tapInSheet('Asystuję');
    expect(find.text('Assisted workshop'), findsOneWidget);
    expect(find.text('Pole dance'), findsNothing);
    await tapInSheet('Prowadzę');
    expect(find.text('Assisted workshop'), findsNothing);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('PT with Tomasz'), findsOneWidget);
    await tapInSheet('Wszystkie');

    await tapInSheet('Gym');
    expect(find.text('Pole dance'), findsNothing);
    expect(find.text('PT with Tomasz'), findsOneWidget);

    await tapInSheet('Pokaż odwołane');
    expect(find.text('PT with Tomasz'), findsNothing);
    expect(find.text('Assisted workshop'), findsOneWidget);

    Navigator.of(tester.element(find.byType(BottomSheet))).pop();
    await tester.pumpAndSettle();
    expect(find.text('Filtry (2)'), findsOneWidget);
    await tester.tap(find.text('Wyczyść'));
    await tester.pumpAndSettle();
    expect(find.text('Filtry'), findsOneWidget);
    expect(find.text('Pole dance'), findsOneWidget);
    expect(find.text('PT with Tomasz'), findsOneWidget);
  });

  test('a role filter needs the trainer among the event staff', () {
    const filter = TrainerScheduleFilter(role: TrainerRoleFilter.lead);
    final led = _event('a', EventKind.groupClass, today);
    final assisted = _event('b', EventKind.groupClass, today, isLead: false);

    expect(filter.matches(led, 'staff-1'), isTrue);
    expect(filter.matches(assisted, 'staff-1'), isFalse);
    expect(filter.matches(led, 'someone-else'), isFalse);
    expect(const TrainerScheduleFilter().activeCount, 0);
    expect(
      const TrainerScheduleFilter(
        kinds: {EventKind.workshop},
        showCancelled: false,
      ).activeCount,
      2,
    );
  });

  test('cancelled events are red and kinds have their own colours', () {
    final scheme = ColorScheme.fromSeed(seedColor: Colors.indigo);
    final group = _event('a', EventKind.groupClass, today);
    final personal = _event('b', EventKind.personalTraining, today);
    final cancelled = _event(
      'c',
      EventKind.groupClass,
      today,
      status: EventStatus.cancelled,
    );

    expect(scheduleEventColor(cancelled, scheme), scheme.error);
    expect(
      scheduleEventColor(group, scheme),
      isNot(scheduleEventColor(personal, scheme)),
    );
    expect(scheduleEventColor(group, scheme), isNot(scheme.error));
  });
}
