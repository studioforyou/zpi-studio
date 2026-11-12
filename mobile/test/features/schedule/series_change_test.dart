import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_screen.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_screen.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_change_widgets.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_changes_banner.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/features/trainings/presentation/trainer_event_details_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

import '../trainings/group_class_fixtures.dart';

const _scope = (accountId: 'parent', profileId: 'self');

const _client = Session(
  accountId: 'parent',
  displayName: 'Anna Klient',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [Profile(id: 'self', displayName: 'Anna Klient', isChild: false)],
  activeProfileId: 'self',
);

const _trainer = Session(
  accountId: 'trainer-account',
  displayName: 'Anna Trener',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [],
  activeProfileId: null,
);

class _ClientAuth extends AuthController {
  @override
  AuthState build() => const Authenticated(_client);
}

class _TrainerAuth extends AuthController {
  @override
  AuthState build() => const Authenticated(_trainer);
}

String _utc(DateTime time) => time.toUtc().toIso8601String();

/// A class of the series that usually starts at [usualStart], with the given
/// one-off change.
Map<String, dynamic> _changedClass({
  required String id,
  required DateTime startsAt,
  DateTime? usualStart,
  List<String>? usualInstructors,
  List<String>? usualRooms,
  bool reserved = true,
  String title = 'Pole dance',
}) => {
  ...groupClassJson(reserved: reserved, title: title),
  'id': id,
  'startsAt': _utc(startsAt),
  'endsAt': _utc(startsAt.add(const Duration(hours: 1))),
  'seriesChange': {
    'usualStartsAt': usualStart == null ? null : _utc(usualStart),
    'usualEndsAt': usualStart == null
        ? null
        : _utc(usualStart.add(const Duration(hours: 1))),
    'usualInstructors': usualInstructors,
    'usualRooms': usualRooms,
  },
};

/// Returns the classes overlapping the window, and like the backend also
/// those moved away from a usual time in it.
class _ClassesApi implements GroupClassesApi {
  _ClassesApi(this.classes);

  final List<Map<String, dynamic>> classes;

  bool _inWindow(GroupClass event, ClassesQuery query) {
    final usual = event.seriesChange?.usualStartsAt;
    return (event.startsAt.isBefore(query.window.to) &&
            event.endsAt.isAfter(query.window.from)) ||
        (usual != null &&
            !usual.isBefore(query.window.from) &&
            usual.isBefore(query.window.to));
  }

  @override
  Future<List<GroupClass>> fetchClasses(ClassesQuery query) async => [
    for (final json in classes)
      if (GroupClass.fromJson(json) case final event
          when _inWindow(event, query))
        event,
  ];

  @override
  Future<List<GroupClass>> fetchReservations(ClassesQuery query) async => [
    for (final event in await fetchClasses(query))
      if (event.isReserved) event,
  ];

  @override
  Future<GroupClass> fetchClass(ClassQuery query) async => GroupClass.fromJson(
    classes.singleWhere((json) => json['id'] == query.eventId),
  );

  @override
  Future<List<ClassParticipant>> fetchParticipants(ClassQuery query) async =>
      const [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _ServiceTypesApi implements ScheduleServiceTypesApi {
  @override
  Future<Map<String, ScheduleServiceType>> fetchAll() async => const {};
}

class _TrainersApi implements StudioTrainersApi {
  @override
  Future<List<StudioTrainer>> fetchAll() async => const [];
}

class _ScheduleApi implements ScheduleApi {
  _ScheduleApi(this.events);

  final List<ScheduleEvent> events;

  bool _usualIn(ScheduleEvent event, ScheduleWindow window) {
    final usual = event.seriesChange?.usualStartsAt;
    return usual != null &&
        !usual.isBefore(window.from) &&
        usual.isBefore(window.to);
  }

  @override
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async => [
    for (final event in events)
      if ((event.startsAt.isBefore(window.to) &&
              event.endsAt.isAfter(window.from)) ||
          _usualIn(event, window))
        event,
  ];

  @override
  Future<ScheduleEvent> fetchOwnEvent(String eventId) async =>
      events.singleWhere((event) => event.id == eventId);
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

ScheduleEvent _trainerEvent(
  String id,
  DateTime startsAt, {
  required String lead,
  required String leadName,
  SeriesChange? change,
}) => ScheduleEvent(
  id: id,
  kind: EventKind.groupClass,
  status: EventStatus.scheduled,
  title: 'Stretching',
  serviceTypeName: null,
  startsAt: startsAt,
  endsAt: startsAt.add(const Duration(hours: 1)),
  capacity: 10,
  staff: [EventStaff(staffId: lead, displayName: leadName, isLead: true)],
  roomNames: const ['Sala A'],
  seriesChange: change,
);

Map<String, dynamic> _notice(
  String id, {
  required String title,
  String kind = 'CHANGED',
  DateTime? previousStartsAt,
  DateTime? previousEndsAt,
  List<String> previousRooms = const ['Sala A'],
  List<String> rooms = const ['Sala A'],
  List<String> previousLeads = const ['Anna Trener'],
  List<String> leads = const ['Anna Trener'],
}) {
  final startsAt = DateTime(2030, 11, 5, 18);
  final endsAt = DateTime(2030, 11, 5, 19);
  return {
    'id': id,
    'eventId': 'event-$id',
    'kind': kind,
    'eventName': title,
    'previousStartsAt': _utc(previousStartsAt ?? startsAt),
    'previousEndsAt': _utc(previousEndsAt ?? endsAt),
    'startsAt': _utc(startsAt),
    'endsAt': _utc(endsAt),
    'previousRooms': previousRooms,
    'rooms': rooms,
    'previousLeads': previousLeads,
    'leads': leads,
    'createdAt': _utc(DateTime(2030, 11, 1)),
  };
}

/// The open notices kept by the server, closed as asked.
class _ChangesApi implements ScheduleChangesApi {
  _ChangesApi([List<Map<String, dynamic>> notices = const []])
    : _notices = [...notices];

  final List<Map<String, dynamic>> _notices;
  final dismissed = <String>[];
  bool dismissedAll = false;

  @override
  Future<List<ScheduleChangeNotice>> fetch(ChangesOwner owner) async => [
    for (final notice in _notices) ScheduleChangeNotice.fromJson(notice),
  ];

  @override
  Future<void> dismiss(ChangesOwner owner, String noticeId) async {
    dismissed.add(noticeId);
    _notices.removeWhere((notice) => notice['id'] == noticeId);
  }

  @override
  Future<void> dismissAll(ChangesOwner owner) async {
    dismissedAll = true;
    _notices.clear();
  }
}

Widget _app(Widget home) => MaterialApp(
  locale: const Locale('pl'),
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: home,
);

void _tallView(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 3000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  group('changeLabel', () {
    final l10n = lookupAppLocalizations(const Locale('pl'));
    final start = DateTime(2030, 11, 5, 18, 30);

    test('names what changed', () {
      expect(
        changeLabel(
          l10n,
          SeriesChange(
            usualStartsAt: DateTime(2030, 11, 5, 17),
            usualEndsAt: DateTime(2030, 11, 5, 18),
            usualRooms: const ['Sala A'],
          ),
          start,
        ),
        'Zmiana godziny i sali',
      );
      expect(
        changeLabel(
          l10n,
          SeriesChange(
            usualStartsAt: DateTime(2030, 11, 4, 17),
            usualEndsAt: DateTime(2030, 11, 4, 18),
            usualRooms: const ['Sala A'],
            usualInstructors: const ['Beata Lis'],
          ),
          start,
        ),
        'Zmiana terminu, sali i prowadzącego',
      );
      expect(
        changeLabel(
          l10n,
          const SeriesChange(usualInstructors: ['Beata Lis']),
          start,
        ),
        'Zastępstwo',
      );
      expect(
        changeLabel(l10n, const SeriesChange(usualRooms: ['Sala A']), start),
        'Zmiana sali',
      );
    });
  });

  group('participant', () {
    // Tuesday 5 November 2030: the class usually starts at 17:00.
    final usual = DateTime(2030, 11, 5, 17);

    Future<void> pump(
      WidgetTester tester,
      List<Map<String, dynamic>> classes, {
      Widget? home,
      bool reservations = true,
      _ChangesApi? changes,
    }) async {
      _tallView(tester);
      await tester.pumpWidget(
        ProviderScope(
          retry: (_, _) => null,
          overrides: [
            authControllerProvider.overrideWith(_ClientAuth.new),
            groupClassesApiProvider.overrideWithValue(_ClassesApi(classes)),
            scheduleServiceTypesApiProvider.overrideWithValue(
              _ServiceTypesApi(),
            ),
            studioTrainersApiProvider.overrideWithValue(_TrainersApi()),
            scheduleChangesApiProvider.overrideWithValue(
              changes ?? _ChangesApi(),
            ),
            personalTrainingsProvider.overrideWith(
              (ref, query) async => const [],
            ),
          ],
          child: _app(
            home ??
                ClientScheduleScreen(
                  reservationsOnly: reservations,
                  today: DateTime(2030, 11, 4),
                ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('a moved class shows its new time and the usual one', (
      tester,
    ) async {
      await pump(tester, [
        _changedClass(
          id: 'class-1',
          startsAt: DateTime(2030, 11, 5, 18, 30),
          usualStart: usual,
          usualRooms: ['Sala Zielona'],
        ),
      ]);

      expect(find.text('Zmiana godziny i sali'), findsOneWidget);
      expect(find.textContaining('18:30–19:30'), findsOneWidget);
      expect(find.textContaining('17:00–18:00'), findsOneWidget);
      expect(find.textContaining('Sala Zielona'), findsOneWidget);
      // The usual slot keeps a placeholder that says where the class went.
      expect(find.text('Pole dance, zwykle 17:00'), findsOneWidget);
      expect(find.text('Przeniesione na 18:30'), findsOneWidget);
    });

    testWidgets('a class moved to another week leaves its usual day a '
        'placeholder', (tester) async {
      await pump(tester, [
        _changedClass(
          id: 'class-1',
          startsAt: DateTime(2030, 11, 12, 10),
          usualStart: usual,
        ),
      ], reservations: false);

      expect(find.text('Pole dance, zwykle 17:00'), findsOneWidget);
      expect(find.text('Przeniesione na wt. 12.11, 10:00'), findsOneWidget);
      // The class itself is in the week it moved to.
      expect(find.text('Zmiana terminu'), findsNothing);

      await tester.tap(find.text('Pole dance, zwykle 17:00'));
      await tester.pumpAndSettle();
      expect(find.byType(GroupClassDetailsScreen), findsOneWidget);
      expect(find.text('Jednorazowa zmiana'), findsOneWidget);
      expect(find.text('Termin'), findsOneWidget);
    });

    testWidgets('the details list what changed and the moved deadline', (
      tester,
    ) async {
      await pump(
        tester,
        [
          _changedClass(
            id: 'class-1',
            startsAt: DateTime(2030, 11, 5, 18, 30),
            usualStart: usual,
            usualInstructors: ['Beata Lis'],
          ),
        ],
        home: const GroupClassDetailsScreen(
          query: (scope: _scope, eventId: 'class-1'),
        ),
      );

      expect(find.text('Jednorazowa zmiana'), findsOneWidget);
      expect(find.text('Godzina'), findsOneWidget);
      expect(find.text('Prowadzący'), findsOneWidget);
      // In the change card and crossed out in the summary.
      expect(find.textContaining('Beata Lis'), findsNWidgets(2));
      expect(
        find.text(
          'Termin bezpłatnego odwołania przesunął się razem z zajęciami.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('a class as usual has no change', (tester) async {
      await pump(tester, [
        {
          ...groupClassJson(reserved: true),
          'startsAt': _utc(usual),
          'endsAt': _utc(usual.add(const Duration(hours: 1))),
        },
      ]);

      expect(find.byType(ChangeChip), findsNothing);
      expect(find.byType(MovedAwayTile), findsNothing);
    });

    testWidgets('the banner lists the changes of booked classes until '
        'each is closed', (tester) async {
      final changes = _ChangesApi([
        _notice(
          'n-1',
          title: 'Stretching',
          previousRooms: ['Sala A'],
          rooms: ['Sala B'],
        ),
      ]);
      await pump(tester, const [], changes: changes);

      expect(find.text('Zmiana w Twoich zajęciach'), findsOneWidget);
      expect(find.textContaining('Zmiana sali: Stretching'), findsOneWidget);
      await tester.tap(find.text('Pokaż'));
      await tester.pumpAndSettle();
      expect(find.text('Sala A → Sala B'), findsOneWidget);

      await tester.tap(find.byTooltip('Zamknij'));
      await tester.pumpAndSettle();
      expect(changes.dismissed, ['n-1']);
      expect(find.textContaining('Zmiana sali'), findsNothing);
    });
  });

  group('trainer', () {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);

    Future<void> pump(WidgetTester tester, List<ScheduleEvent> events) async {
      _tallView(tester);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            authControllerProvider.overrideWith(_TrainerAuth.new),
            scheduleApiProvider.overrideWithValue(_ScheduleApi(events)),
            scheduleChangesApiProvider.overrideWithValue(_ChangesApi()),
            ownPersonalTrainingsProvider.overrideWith(
              (ref, query) async => PersonalTrainingsSnapshot(
                checkedOn: query.window.from,
                checkedAt: DateTime.now(),
                items: const [],
              ),
            ),
            eventParticipantsApiProvider.overrideWithValue(_ParticipantsApi()),
            scheduleServiceTypesApiProvider.overrideWithValue(
              _ServiceTypesApi(),
            ),
          ],
          child: _app(const TrainerScheduleScreen()),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('a stand-in sees whom they replace', (tester) async {
      await pump(tester, [
        _trainerEvent(
          'class-1',
          DateTime(today.year, today.month, today.day, 19),
          lead: 'staff-1',
          leadName: 'Anna Trener',
          change: const SeriesChange(usualInstructors: ['Beata Lis']),
        ),
      ]);

      expect(find.text('Zastępstwo'), findsOneWidget);
      expect(
        find.text('Prowadzący: Anna Trener Beata Lis', findRichText: true),
        findsOneWidget,
      );

      await tester.tap(find.text('Stretching'));
      await tester.pumpAndSettle();
      expect(find.byType(TrainerEventDetailsScreen), findsOneWidget);
      expect(find.text('Jednorazowa zmiana'), findsOneWidget);
      expect(find.text('Zastępstwo za: Beata Lis'), findsOneWidget);
    });

    testWidgets('the usual trainer sees who runs their class this once', (
      tester,
    ) async {
      await pump(tester, [
        _trainerEvent(
          'class-1',
          DateTime(today.year, today.month, today.day, 19),
          lead: 'staff-2',
          leadName: 'Marek Nowak',
          change: const SeriesChange(usualInstructors: ['Anna Trener']),
        ),
      ]);

      expect(find.text('Stretching, 19:00–20:00'), findsOneWidget);
      expect(find.text('Prowadzi Marek Nowak (zastępstwo)'), findsOneWidget);
      expect(find.text('Zastępstwo'), findsNothing);
    });

    testWidgets('a moved class keeps a placeholder at its usual time', (
      tester,
    ) async {
      final usual = DateTime(today.year, today.month, today.day, 17);
      await pump(tester, [
        _trainerEvent(
          'class-1',
          DateTime(today.year, today.month, today.day, 18, 30),
          lead: 'staff-1',
          leadName: 'Anna Trener',
          change: SeriesChange(
            usualStartsAt: usual,
            usualEndsAt: usual.add(const Duration(hours: 1)),
          ),
        ),
      ]);

      expect(find.text('Zmiana godziny'), findsOneWidget);
      expect(find.text('Stretching, zwykle 17:00'), findsOneWidget);
      expect(find.text('Przeniesione na 18:30'), findsOneWidget);
    });
  });

  group('notices', () {
    testWidgets('a trainer hears about each change: assigned, moved and '
        'taken off again', (tester) async {
      _tallView(tester);
      final changes = _ChangesApi([
        _notice('n-3', title: 'Pole dance', kind: 'UNASSIGNED'),
        _notice(
          'n-2',
          title: 'Pole dance',
          previousStartsAt: DateTime(2030, 11, 5, 17),
          previousEndsAt: DateTime(2030, 11, 5, 18),
        ),
        _notice('n-1', title: 'Pole dance', kind: 'ASSIGNED'),
        _notice(
          'n-0',
          title: 'Stretching',
          previousLeads: ['Beata Lis'],
          leads: ['Anna Trener'],
        ),
      ]);
      String? opened;
      await tester.pumpWidget(
        ProviderScope(
          overrides: [scheduleChangesApiProvider.overrideWithValue(changes)],
          child: _app(
            Scaffold(
              body: ScheduleChangesBanner(
                owner: const TrainerChanges((
                  accountId: 'trainer-account',
                  staffId: 'staff-1',
                )),
                onOpen: (id) => opened = id,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Zmiany w 4 Twoich zajęciach'), findsOneWidget);
      expect(
        find.textContaining('Odebrano Ci zajęcia: Pole dance'),
        findsOneWidget,
      );
      expect(find.textContaining('Zmiana godziny: Pole dance'), findsOneWidget);
      expect(
        find.textContaining('Przypisano Cię do zajęć: Pole dance'),
        findsOneWidget,
      );
      expect(find.text('i jeszcze 1'), findsOneWidget);

      await tester.tap(find.text('Pokaż'));
      await tester.pumpAndSettle();
      expect(find.text('17:00–18:00 → 18:00–19:00'), findsOneWidget);
      expect(find.text('Prowadzący: Beata Lis → Anna Trener'), findsOneWidget);
      // A class taken off cannot be opened.
      await tester.tap(find.textContaining('Odebrano Ci zajęcia').last);
      await tester.pumpAndSettle();
      expect(opened, isNull);
      await tester.tap(find.textContaining('Przypisano Cię').last);
      await tester.pumpAndSettle();
      expect(opened, 'event-n-1');

      await tester.tap(find.text('Pokaż'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Zamknij wszystkie'));
      await tester.pumpAndSettle();
      expect(changes.dismissedAll, isTrue);
      expect(find.text('Pokaż'), findsNothing);
    });

    testWidgets('the tab counts the notices until they are closed', (
      tester,
    ) async {
      final changes = _ChangesApi([
        _notice('n-1', title: 'Stretching'),
        _notice('n-2', title: 'Stretching'),
      ]);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            authControllerProvider.overrideWith(_ClientAuth.new),
            scheduleChangesApiProvider.overrideWithValue(changes),
          ],
          child: _app(
            Consumer(
              builder: (context, ref, _) => TextButton(
                onPressed: () => dismissAllScheduleChanges(
                  ref,
                  ref.read(currentChangesOwnerProvider)!,
                ),
                child: Text('${watchOpenScheduleChanges(ref)}'),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('2'), findsOneWidget);
      await tester.tap(find.text('2'));
      await tester.pumpAndSettle();
      expect(changes.dismissedAll, isTrue);
      expect(find.text('0'), findsOneWidget);
    });
  });
}
