import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_screen.dart';
import 'package:mobile/features/trainings/presentation/trainings_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session _session({
  String account = 'trainer',
  String staff = 'staff',
  AppRole role = AppRole.trainer,
}) => Session(
  accountId: account,
  displayName: 'Marek',
  roles: const [AppRole.trainer, AppRole.client],
  activeRole: role,
  staffProfileId: staff,
  profiles: const [],
  activeProfileId: null,
);

class _Auth extends AuthController {
  @override
  AuthState build() => Authenticated(_session());
  void replace(Session session) => state = Authenticated(session);
}

PersonalTrainingsSnapshot _snapshot(
  ScheduleWindow window, {
  String firstName = 'Tomasz Lewandowski',
  String eventId = 'shared-event',
}) {
  final start = window.from.add(const Duration(days: 1, hours: 9));
  return PersonalTrainingsSnapshot(
    checkedOn: window.from,
    // The server snapshot precedes these sessions, even when the device is later.
    checkedAt: window.from.subtract(const Duration(days: 1)),
    items: [
      for (final (customer, name) in [
        ('customer-1', firstName),
        ('customer-2', 'Anna Nowak'),
      ])
        TraineeTraining(
          event: ScheduleEvent(
            id: eventId,
            kind: EventKind.personalTraining,
            status: EventStatus.scheduled,
            title: 'Trening personalny',
            serviceTypeName: 'Trening personalny',
            startsAt: start,
            endsAt: start.add(const Duration(hours: 1)),
            capacity: 2,
            staff: const [],
            roomNames: const ['Sala PT'],
          ),
          reservationId: 'reservation-$customer',
          customerId: customer,
          customerName: name,
          trainerConfirmedAt: null,
          clientConfirmedAt: null,
          attendance: 'UNMARKED',
          settlementStatus: 'OPEN',
          funding: 'PASS_RESERVED',
          passId: 'pass-$customer',
          passName: 'Pakiet PT',
          requiresAttention: false,
        ),
    ],
  );
}

class _Api implements TraineeOverviewApi {
  bool fails = false;
  String name = 'Tomasz Lewandowski';
  int otherCalls = 0;
  final windows = <ScheduleWindow>[];
  Future<PersonalTrainingsSnapshot> Function(ScheduleWindow)? load;
  @override
  Future<PersonalTrainingsSnapshot> fetchOwnCalendar(
    ScheduleWindow window,
  ) async {
    windows.add(window);
    if (fails) {
      throw const ApiException(statusCode: 503, message: 'Unavailable');
    }
    return load?.call(window) ?? _snapshot(window, firstName: name);
  }

  @override
  Future<TraineeOverviewList> fetchList() {
    otherCalls++;
    throw UnimplementedError(
      'The shared calendar must not load per-trainee summaries.',
    );
  }

  @override
  Future<TraineeOverviewDetails> fetchDetails(String customerId) {
    otherCalls++;
    throw UnimplementedError(
      'The shared calendar must not load every trainee.',
    );
  }

  @override
  Future<List<TraineeTraining>> fetchCalendar(
    String customerId,
    ScheduleWindow window,
  ) {
    otherCalls++;
    throw UnimplementedError('Use the shared endpoint.');
  }
}

class _NoTrainees extends TraineesApi {
  _NoTrainees() : super(Dio());
  @override
  Future<List<Trainee>> fetchOwnTrainees() async => [];
}

class _ScheduleApi extends ScheduleApi {
  _ScheduleApi() : super(Dio());
  final windows = <ScheduleWindow>[];

  @override
  Future<List<ScheduleEvent>> fetchOwnEvents(ScheduleWindow window) async {
    windows.add(window);
    final training = _snapshot(window).items.first;
    ScheduleEvent event(String id, EventKind kind, String title) =>
        ScheduleEvent(
          id: id,
          kind: kind,
          status: EventStatus.scheduled,
          title: title,
          serviceTypeName: title,
          startsAt: training.startsAt,
          endsAt: training.endsAt,
          capacity: 2,
          staff: const [],
          roomNames: const ['Sala PT'],
        );
    return [
      event('shared-event', EventKind.personalTraining, 'Original PT event'),
      event('unbooked', EventKind.personalTraining, 'Wolny termin personalny'),
      event('group', EventKind.groupClass, 'Calisthenics'),
    ];
  }
}

class _Adapter implements HttpClientAdapter {
  RequestOptions? request;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    request = options;
    Map<String, dynamic> row(String customer, String name) => {
      'id': 'shared-event',
      'kind': 'PERSONAL_TRAINING',
      'status': 'SCHEDULED',
      'title': 'PT',
      'serviceTypeName': 'PT',
      'startsAt': '2026-11-04T08:00:00Z',
      'endsAt': '2026-11-04T09:00:00Z',
      'capacity': 2,
      'staff': [],
      'resources': [
        {'resourceId': 'room', 'name': 'Sala PT'},
      ],
      'reservationId': 'reservation-$customer',
      'customerId': customer,
      'customerName': name,
      'trainerConfirmedAt': null,
      'clientConfirmedAt': null,
      'attendance': 'UNMARKED',
      'settlementStatus': 'OPEN',
      'funding': 'PASS_RESERVED',
      'passId': 'pass',
      'passName': 'PT',
      'requiresAttention': false,
    };
    return ResponseBody.fromString(
      jsonEncode({
        'checkedOn': '2026-11-04',
        'checkedAt': '2026-11-04T07:00:00Z',
        'items': [row('first', 'Tomasz'), row('second', 'Anna')],
      }),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Api api, {
  bool screen = false,
  bool settle = true,
  double width = 600,
  double scale = 1,
}) async {
  tester.view.physicalSize = Size(width, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      authControllerProvider.overrideWith(_Auth.new),
      traineeOverviewApiProvider.overrideWithValue(api),
      scheduleApiProvider.overrideWithValue(_ScheduleApi()),
      traineesApiProvider.overrideWithValue(_NoTrainees()),
    ],
  );
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox());
    container.dispose();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: screen ? const TrainingsScreen() : const TrainerScheduleScreen(),
      ),
    ),
  );
  if (settle) await tester.pumpAndSettle();
  return container;
}

void main() {
  test(
    'shared GET preserves both participants in one event and snapshot time',
    () async {
      final adapter = _Adapter();
      final api = TraineeOverviewApi(Dio()..httpClientAdapter = adapter);
      final window = (from: DateTime(2026, 11, 2), to: DateTime(2026, 11, 9));
      final result = await api.fetchOwnCalendar(window);
      expect(adapter.request!.method, 'GET');
      expect(adapter.request!.path, '/api/v1/me/trainees/personal-trainings');
      expect(
        adapter.request!.queryParameters['from'],
        window.from.toUtc().toIso8601String(),
      );
      expect(result.checkedAt, DateTime.utc(2026, 11, 4, 7));
      expect(result.items.map((session) => session.customerName), [
        'Tomasz',
        'Anna',
      ]);
      expect(result.items.map((session) => session.id).toSet(), hasLength(2));
      expect(result.items.map((session) => session.eventId).toSet(), {
        'shared-event',
      });
    },
  );

  testWidgets('Trainings opens on trainees without a duplicate schedule', (
    tester,
  ) async {
    final api = _Api();
    await _pump(tester, api, screen: true);
    expect(tester.widget<TabBar>(find.byType(TabBar)).tabs, hasLength(2));
    expect(find.text('Grafik'), findsNothing);
    expect(find.byIcon(Icons.info_outline), findsNothing);
    expect(find.text('Podopieczni'), findsOneWidget);
    expect(find.text('Grupy'), findsOneWidget);
    expect(find.byType(ScheduleCalendar), findsNothing);
    expect(api.windows, isEmpty);
  });

  testWidgets(
    'main weekly schedule enriches personal sessions once and retains group and unbooked events',
    (tester) async {
      final api = _Api();
      await _pump(tester, api);
      expect(find.byIcon(Icons.info_outline), findsNothing);
      expect(
        tester
            .widget<SegmentedButton<ScheduleView>>(
              find.byType(SegmentedButton<ScheduleView>),
            )
            .selected,
        {ScheduleView.week},
      );
      expect(find.text('Tomasz Lewandowski'), findsOneWidget);
      expect(find.text('Anna Nowak'), findsOneWidget);
      expect(find.text('09:00–10:00'), findsNWidgets(4));
      expect(find.text('Original PT event'), findsNothing);
      expect(find.text('Wolny termin personalny'), findsOneWidget);
      expect(find.text('Calisthenics'), findsOneWidget);
      expect(api.windows, hasLength(1));
      expect(api.otherCalls, 0);
      // Future/past confirmation uses the backend snapshot, not the device clock.
      expect(find.text('Zaplanowany'), findsNWidgets(2));
      expect(find.text('Trener'), findsNothing);
      await tester.tap(find.text('Tomasz Lewandowski'));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      expect(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text('Pakiet PT'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'the main schedule keeps an event without trainee details and ignores orphaned details',
    (tester) async {
      final api = _Api()
        ..load = (window) async =>
            _snapshot(window, eventId: 'not-in-main-schedule');
      await _pump(tester, api);
      expect(find.text('Original PT event'), findsOneWidget);
      expect(find.text('Wolny termin personalny'), findsOneWidget);
      expect(find.text('Calisthenics'), findsOneWidget);
      expect(find.text('Tomasz Lewandowski'), findsNothing);
      expect(find.text('Anna Nowak'), findsNothing);
    },
  );

  testWidgets(
    'the personal-training filter retains enriched sessions alongside the other main filters',
    (tester) async {
      final api = _Api();
      await _pump(tester, api);
      await tester.tap(find.text('Filtry'));
      await tester.pumpAndSettle();
      await tester.tap(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text('Trening personalny'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Tomasz Lewandowski'), findsOneWidget);
      expect(find.text('Anna Nowak'), findsOneWidget);
      expect(find.text('Wolny termin personalny'), findsOneWidget);
      expect(find.text('Calisthenics'), findsNothing);
    },
  );

  testWidgets(
    'loads one batch for a changed week and retries failures without loading individual trainees',
    (tester) async {
      final api = _Api()..fails = true;
      await _pump(tester, api);
      expect(find.text('Tomasz Lewandowski'), findsNothing);
      api.fails = false;
      await tester.tap(find.text('Spróbuj ponownie'));
      await tester.pumpAndSettle();
      expect(find.text('Tomasz Lewandowski'), findsOneWidget);
      expect(api.windows, hasLength(2));
      await tester.tap(find.byTooltip('Następny'));
      await tester.pumpAndSettle();
      expect(api.windows, hasLength(3));
      expect(
        api.windows.last.from,
        api.windows.first.from.add(const Duration(days: 7)),
      );
      expect(api.otherCalls, 0);
    },
  );

  testWidgets('refreshes the displayed window after returning to the app', (
    tester,
  ) async {
    final api = _Api();
    await _pump(tester, api);
    await tester.tap(find.byTooltip('Następny'));
    await tester.pumpAndSettle();
    final window = api.windows.last;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(api.windows, hasLength(3));
    expect(api.windows.last, window);
  });

  testWidgets(
    'renders an overnight session on both days without duplicate row keys',
    (tester) async {
      final api = _Api()
        ..load = (window) async {
          final snapshot = _snapshot(window);
          final source = snapshot.items.first;
          final start = window.from.add(
            const Duration(days: 1, hours: 23, minutes: 30),
          );
          final overnight = TraineeTraining(
            event: ScheduleEvent(
              id: source.eventId,
              kind: source.kind,
              status: source.status,
              title: source.title,
              serviceTypeName: source.serviceTypeName,
              startsAt: start,
              endsAt: start.add(const Duration(hours: 1)),
              capacity: source.capacity,
              staff: source.staff,
              roomNames: source.roomNames,
            ),
            reservationId: source.reservationId,
            customerId: source.customerId,
            customerName: source.customerName,
            trainerConfirmedAt: null,
            clientConfirmedAt: null,
            attendance: source.attendance,
            settlementStatus: source.settlementStatus,
            funding: source.funding,
            passId: source.passId,
            passName: source.passName,
            requiresAttention: source.requiresAttention,
          );
          return PersonalTrainingsSnapshot(
            checkedOn: snapshot.checkedOn,
            checkedAt: snapshot.checkedAt,
            items: [overnight],
          );
        };
      await _pump(tester, api);
      expect(find.text('Tomasz Lewandowski'), findsNWidgets(2));
      expect(find.text('23:30–00:30'), findsNWidgets(2));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'does not display a late response from another account or retain trainer rows after a role change',
    (tester) async {
      final late = Completer<PersonalTrainingsSnapshot>();
      final api = _Api()..load = (_) => late.future;
      final container = await _pump(tester, api, settle: false);
      await tester.pump();
      final oldWindow = api.windows.single;
      api.load = null;
      api.name = 'Nowy podopieczny';
      final auth = container.read(authControllerProvider.notifier) as _Auth;
      auth.replace(_session(account: 'new', staff: 'new-staff'));
      await tester.pumpAndSettle();
      late.complete(_snapshot(oldWindow, firstName: 'Dawny podopieczny'));
      await tester.pumpAndSettle();
      expect(find.text('Nowy podopieczny'), findsOneWidget);
      expect(find.text('Dawny podopieczny'), findsNothing);
      auth.replace(
        _session(account: 'new', staff: 'new-staff', role: AppRole.client),
      );
      await tester.pumpAndSettle();
      expect(find.text('Nowy podopieczny'), findsNothing);
      expect(find.text('Anna Nowak'), findsNothing);
      expect(find.text('Calisthenics'), findsNothing);
    },
  );

  testWidgets(
    'keeps time and trainee names readable at 320px and enlarged text',
    (tester) async {
      final api = _Api();
      await _pump(tester, api, width: 320, scale: 1.6);
      expect(find.text('09:00–10:00'), findsWidgets);
      expect(find.text('Tomasz Lewandowski'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Anna Nowak'),
        150,
        scrollable: find.byType(Scrollable).last,
      );
      expect(find.text('Anna Nowak'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
