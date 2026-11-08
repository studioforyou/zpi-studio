import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainee_profile_api.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/presentation/trainee_overview_screen.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

final _checkedOn = DateTime(2026, 11, 4);
final _checkedAt = DateTime(2026, 11, 4, 12);

Session _session({
  String account = 'account',
  String staff = 'staff',
  AppRole role = AppRole.trainer,
}) => Session(
  accountId: account,
  displayName: 'Marek Nowak',
  roles: const [AppRole.trainer, AppRole.client],
  activeRole: role,
  staffProfileId: staff,
  profiles: const [],
  activeProfileId: null,
);

class _Auth extends AuthController {
  @override
  AuthState build() => Authenticated(_session());
  void replace(Session session, {bool choosingRole = false}) =>
      state = Authenticated(session, choosingRole: choosingRole);
}

TraineePass _pass({
  String id = 'pass',
  String name = 'Pakiet PT',
  int? entries = 3,
  String status = 'ACTIVE',
  bool eligible = true,
}) => TraineePass(
  id: id,
  productName: name,
  status: status,
  entriesRemaining: entries,
  validFrom: DateTime(2026, 10, 20),
  validUntil: DateTime(2026, 11, 30),
  serviceTypeIds: const ['pt'],
  eligible: eligible,
  lowBalance: eligible && entries != null && entries > 0 && entries <= 3,
);

TraineeTraining _training({
  String id = 'event',
  String funding = 'PASS_CONSUMED',
  String settlement = 'FINAL',
  bool future = false,
  bool trainer = true,
  bool participant = true,
  bool attention = false,
}) => TraineeTraining(
  event: ScheduleEvent(
    id: id,
    kind: EventKind.personalTraining,
    status: EventStatus.scheduled,
    title: 'Trening personalny',
    serviceTypeName: 'Trening personalny',
    startsAt: future ? DateTime(2026, 11, 5, 18) : DateTime(2026, 11, 4, 9),
    endsAt: future ? DateTime(2026, 11, 5, 19) : DateTime(2026, 11, 4, 10),
    capacity: 1,
    staff: const [],
    roomNames: const ['Sala PT'],
  ),
  reservationId: 'reservation-$id',
  trainerConfirmedAt: trainer ? DateTime(2026, 11, 4, 10) : null,
  clientConfirmedAt: participant ? DateTime(2026, 11, 4, 10) : null,
  attendance: 'PRESENT',
  settlementStatus: settlement,
  funding: funding,
  passId: funding == 'NO_PASS' ? null : 'pass',
  passName: funding == 'NO_PASS' ? null : 'Pakiet PT',
  requiresAttention: attention,
);

TraineeOverview _overview({
  List<TraineePass>? passes,
  bool active = true,
  String availability = 'AVAILABLE',
  TraineeTraining? next,
  TraineeTraining? attention,
  String name = 'Tomasz Lewandowski',
}) => TraineeOverview(
  customerId: 'customer',
  displayName: name,
  isActive: active,
  availability: availability,
  primaryPassId: (passes ?? [_pass()]).firstOrNull?.id,
  passes: passes ?? [_pass()],
  nextTraining: next,
  attentionTraining: attention,
);

class _Api implements TraineeOverviewApi {
  _Api(this.overview);
  TraineeOverview overview;
  bool fails = false;
  int lists = 0;
  int details = 0;
  final calendarCalls = <ScheduleWindow>[];
  List<TraineeTraining> trainings = [];
  Future<TraineeOverviewDetails> Function()? detailLoad;

  @override
  Future<PersonalTrainingsSnapshot> fetchOwnCalendar(
    ScheduleWindow window,
  ) async => PersonalTrainingsSnapshot(
    checkedOn: _checkedOn,
    checkedAt: _checkedAt,
    items: trainings,
  );

  @override
  Future<TraineeOverviewList> fetchList() async {
    lists++;
    if (fails) {
      throw const ApiException(statusCode: 503, message: 'Unavailable');
    }
    return TraineeOverviewList(
      checkedOn: _checkedOn,
      checkedAt: _checkedAt,
      items: [overview],
    );
  }

  @override
  Future<TraineeOverviewDetails> fetchDetails(String customerId) async {
    details++;
    if (fails) {
      throw const ApiException(statusCode: 503, message: 'Unavailable');
    }
    return detailLoad?.call() ??
        TraineeOverviewDetails(
          checkedOn: _checkedOn,
          checkedAt: _checkedAt,
          trainee: overview,
        );
  }

  @override
  Future<List<TraineeTraining>> fetchCalendar(
    String customerId,
    ScheduleWindow window,
  ) async {
    calendarCalls.add(window);
    return trainings
        .where(
          (event) =>
              event.startsAt.isBefore(window.to) &&
              event.endsAt.isAfter(window.from),
        )
        .toList();
  }
}

class _Trainees implements TraineesApi {
  _Trainees({this.active = true});
  final bool active;
  @override
  Future<List<Trainee>> fetchOwnTrainees() async => [
    Trainee(
      customerId: 'customer',
      displayName: 'Tomasz Lewandowski',
      isChild: false,
      birthDate: null,
      isActive: active,
      assignedAt: DateTime(2026, 10, 1),
    ),
  ];
  @override
  Future<Trainee> assign(String customerId) => throw UnimplementedError();
  @override
  Future<List<CustomerSummary>> searchCustomers(String query) async => [];
  @override
  Future<void> unassign(String customerId) async {}
}

class _ProfileApi implements TraineeProfileApi {
  @override
  Future<TraineeProfile> fetch(String customerId) async => TraineeProfile(
    customerId: customerId,
    displayName: 'Tomasz Lewandowski',
    isChild: false,
    birthDate: DateTime(1990, 4, 12),
    phone: '+48 600 100 200',
    emergencyContactName: 'Anna Lewandowska',
    emergencyPhone: '+48 600 300 400',
    guardians: const [],
    assignedAt: DateTime(2026, 10, 1),
  );
}

class _Adapter implements HttpClientAdapter {
  Map<String, dynamic> body = {};
  int status = 200;
  final calls = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls.add(options);
    return ResponseBody.fromString(
      jsonEncode(body),
      status,
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
  bool list = false,
  bool active = true,
  bool settle = true,
  double width = 600,
  double height = 1300,
  double scale = 1,
}) async {
  tester.view.physicalSize = Size(width, height);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      authControllerProvider.overrideWith(_Auth.new),
      traineeOverviewApiProvider.overrideWithValue(api),
      traineeProfileApiProvider.overrideWithValue(_ProfileApi()),
      traineesApiProvider.overrideWithValue(_Trainees(active: active)),
    ],
  );
  final router = GoRouter(
    initialLocation: list
        ? '/trainer/trainings'
        : '/trainer/trainings/trainees/customer',
    routes: [
      GoRoute(
        path: '/trainer/trainings',
        builder: (_, _) => const Scaffold(body: TraineesTab()),
        routes: [
          GoRoute(
            path: 'trainees/:customerId',
            builder: (_, state) => TraineeOverviewScreen(
              customerId: state.pathParameters['customerId']!,
            ),
          ),
        ],
      ),
    ],
  );
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox());
    router.dispose();
    container.dispose();
  });
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
      ),
    ),
  );
  if (settle) await tester.pumpAndSettle();
  return container;
}

void main() {
  test(
    'reads package and session contracts with UTC calendar bounds using GET only',
    () async {
      final adapter = _Adapter();
      final api = TraineeOverviewApi(Dio()..httpClientAdapter = adapter);
      adapter.body = {
        'checkedOn': '2026-11-04',
        'checkedAt': '2026-11-04T12:00:00Z',
        'items': [
          {
            'customerId': 'customer',
            'displayName': 'Tomasz',
            'isActive': true,
            'availability': 'AVAILABLE',
            'primaryPassId': 'pass',
            'passes': [
              {
                'id': 'pass',
                'productName': 'PT',
                'status': 'ACTIVE',
                'entriesRemaining': null,
                'validFrom': '2026-10-01',
                'validUntil': '2026-11-30',
                'serviceTypeIds': ['pt'],
                'eligible': true,
                'lowBalance': false,
              },
            ],
            'nextTraining': null,
            'attentionTraining': null,
          },
        ],
      };
      final result = await api.fetchList();
      expect(result.checkedOn, DateTime(2026, 11, 4));
      expect(result.checkedAt.isUtc, isTrue);
      expect(result.items.single.primaryPass!.entriesRemaining, isNull);
      expect(
        result.items.single.passes.single.validUntil,
        DateTime(2026, 11, 30),
      );
      adapter.body = {'items': []};
      final window = (from: DateTime(2026, 11, 2), to: DateTime(2026, 11, 9));
      await api.fetchCalendar('customer', window);
      expect(
        adapter.calls.last.path,
        '/api/v1/me/trainees/customer/personal-trainings',
      );
      expect(
        adapter.calls.last.queryParameters['from'],
        window.from.toUtc().toIso8601String(),
      );
      expect(adapter.calls.every((request) => request.method == 'GET'), isTrue);
      adapter.status = 403;
      adapter.body = {'code': 'ACCESS_DENIED', 'message': 'Denied'};
      await expectLater(
        api.fetchDetails('customer'),
        throwsA(
          isA<ApiException>().having(
            (error) => error.statusCode,
            'status',
            403,
          ),
        ),
      );
    },
  );

  testWidgets(
    'opens a real trainee card with balance, validity, next term and a separate attention alert',
    (tester) async {
      final api = _Api(
        _overview(
          next: _training(future: true, funding: 'PASS_RESERVED'),
          attention: _training(settlement: 'DISPUTED', attention: true),
        ),
      );
      await _pump(tester, api, list: true);
      expect(find.text('3 wejścia'), findsOneWidget);
      expect(find.textContaining('do 30.11.2026'), findsOneWidget);
      expect(find.byIcon(Icons.warning_amber_rounded), findsAtLeastNWidgets(1));
      expect(find.byTooltip('Najbliższy trening'), findsOneWidget);
      expect(find.byType(TraineeNextTraining), findsNothing);
      final card = find
          .ancestor(
            of: find.text('Tomasz Lewandowski'),
            matching: find.byType(Card),
          )
          .first;
      expect(tester.getSize(card).height, lessThan(200));
      expect(find.textContaining('Wymaga uwagi'), findsOneWidget);
      expect(api.lists, 1);
      await tester.tap(find.text('Tomasz Lewandowski'));
      await tester.pumpAndSettle();
      expect(find.byType(TraineeOverviewScreen), findsOneWidget);
      expect(api.details, 1);
      expect(find.widgetWithText(Tab, 'Pakiety'), findsOneWidget);
      expect(find.widgetWithText(Tab, 'Historia'), findsOneWidget);
    },
  );

  testWidgets(
    'puts the nearest training before packages and opens its date, room and funding details',
    (tester) async {
      final api = _Api(
        _overview(next: _training(future: true, funding: 'PASS_RESERVED')),
      );
      await _pump(tester, api);

      final next = find.byType(TraineeNextTraining);
      expect(next, findsOneWidget);
      expect(
        find.descendant(of: next, matching: find.text('Najbliższy trening')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: next, matching: find.textContaining('18:00')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: next, matching: find.textContaining('19:00')),
        findsOneWidget,
      );
      expect(
        tester.getTopLeft(next).dy,
        lessThan(tester.getTopLeft(find.byType(TraineePassCard).first).dy),
      );

      await tester.tap(next);
      await tester.pumpAndSettle();
      final sheet = find.byType(BottomSheet);
      expect(sheet, findsOneWidget);
      expect(
        find.descendant(of: sheet, matching: find.text('Sala PT')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: sheet, matching: find.text('Pakiet PT')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: sheet,
          matching: find.text('Wejście zarezerwowane'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'shows several packages separately including zero, unlimited and suspended',
    (tester) async {
      final api = _Api(
        _overview(
          passes: [
            _pass(entries: 0),
            _pass(id: 'unlimited', name: 'PT Open', entries: null),
            _pass(
              id: 'suspended',
              name: 'PT zawieszony',
              status: 'SUSPENDED',
              eligible: false,
            ),
          ],
        ),
      );
      await _pump(tester, api);
      expect(find.text('Brak wejść'), findsOneWidget);
      expect(find.byType(TraineePassCard), findsOneWidget);
      expect(find.text('Bez limitu wejść'), findsNothing);
      expect(find.text('Zawieszony'), findsNothing);
      await tester.tap(find.text('Pozostałe pakiety (2)'));
      await tester.pumpAndSettle();
      expect(find.text('Bez limitu wejść'), findsOneWidget);
      expect(find.text('Zawieszony'), findsOneWidget);
      expect(find.byType(TraineePassCard), findsNWidgets(3));
      await tester.tap(find.byTooltip('Jak czytać pakiet'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Wejścia przypisane do rezerwacji'),
        findsOneWidget,
      );
      expect(find.text('Trener i uczestnik'), findsNothing);
      expect(find.text('Rozliczony'), findsNothing);
    },
  );

  testWidgets(
    'package help stays with packages and the warning explains its threshold on tap',
    (tester) async {
      final api = _Api(_overview());
      await _pump(tester, api, list: true);
      await tester.tap(find.byTooltip('Kończy się pakiet'));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsOneWidget);
      expect(find.textContaining('1–3 wolne wejścia'), findsOneWidget);
      expect(find.byType(TraineeOverviewScreen), findsNothing);
      await tester.tap(find.text('Zamknij'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Tomasz Lewandowski'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('Jak czytać pakiet'), findsOneWidget);
      await tester.tap(find.text('Historia'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('Jak czytać pakiet'), findsNothing);
      expect(find.byTooltip('Oznaczenia treningu'), findsNothing);
    },
  );

  testWidgets(
    'attention opens the relevant training with an actual reason and a contextual legend',
    (tester) async {
      final api = _Api(
        _overview(
          attention: _training(
            trainer: false,
            settlement: 'OPEN',
            funding: 'PASS_RESERVED',
            attention: true,
          ),
        ),
      );
      await _pump(tester, api);
      await tester.tap(find.textContaining('Wymaga uwagi'));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      expect(
        find.textContaining(
          'Brakuje potwierdzenia odbycia treningu przez trenera.',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Rozliczenie tego treningu nie jest jeszcze zakończone.',
        ),
        findsOneWidget,
      );
      await tester.tap(find.byTooltip('Oznaczenia treningu'));
      await tester.pumpAndSettle();
      final dialog = find.byType(AlertDialog);
      expect(dialog, findsOneWidget);
      for (final label in [
        'Wymaga uwagi',
        'Do sprawdzenia',
        'Rozliczony',
        'Trener i uczestnik',
        'Odróbka',
        'Poza pakietem',
      ]) {
        expect(
          find.descendant(of: dialog, matching: find.text(label)),
          findsOneWidget,
        );
      }
      expect(
        find.textContaining('Potwierdzenia obu osób są pokazane osobno.'),
        findsOneWidget,
      );
      expect(find.text('Jak czytać pakiet'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  for (final (funding, settlement, reason) in [
    ('PASS_CONSUMED', 'DISPUTED', 'Do treningu zgłoszono spór'),
    ('INCONSISTENT', 'FINAL', 'Dane o wykorzystaniu wejścia są niespójne'),
  ]) {
    testWidgets(
      'attention explains $settlement / $funding without implying settlement',
      (tester) async {
        final api = _Api(
          _overview(
            attention: _training(
              funding: funding,
              settlement: settlement,
              attention: true,
            ),
          ),
        );
        await _pump(tester, api);
        await tester.tap(find.textContaining('Wymaga uwagi'));
        await tester.pumpAndSettle();
        expect(find.textContaining(reason), findsOneWidget);
        expect(find.text('Rozliczony'), findsNothing);
        Navigator.of(tester.element(find.byType(BottomSheet))).pop();
        await tester.pumpAndSettle();
      },
    );
  }

  testWidgets('training help scrolls on a narrow screen with enlarged text', (
    tester,
  ) async {
    final api = _Api(_overview(attention: _training(attention: true)));
    await _pump(tester, api, width: 320, height: 900, scale: 1.6);
    await tester.tap(find.textContaining('Wymaga uwagi'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Oznaczenia treningu'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Poza pakietem'),
      200,
      scrollable: find
          .descendant(
            of: find.byType(AlertDialog),
            matching: find.byType(Scrollable),
          )
          .last,
    );
    expect(find.text('Poza pakietem').hitTestable(), findsOneWidget);
    expect(find.text('Zamknij').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'dispute and consumed entry remain separate, while no-pass stays neutral',
    (tester) async {
      final api = _Api(_overview())
        ..trainings = [
          _training(
            settlement: 'DISPUTED',
            participant: false,
            attention: true,
          ),
          _training(id: 'cash', funding: 'NO_PASS', settlement: 'OPEN'),
        ];
      await _pump(tester, api);
      await tester.tap(find.text('Historia'));
      await tester.pumpAndSettle();
      expect(find.byType(ScheduleCalendar), findsOneWidget);
      expect(find.text('Spór'), findsOneWidget);
      expect(find.text('Wejście pobrane'), findsOneWidget);
      expect(find.text('Rozliczony'), findsNothing);
      expect(find.text('Poza pakietem'), findsOneWidget);
      expect(find.textContaining('Nieopłacony'), findsNothing);
      await tester.tap(find.text('Dzień'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Miesiąc'));
      await tester.pumpAndSettle();
      expect(api.calendarCalls.length, greaterThanOrEqualTo(3));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'future training is planned and does not imply overdue attendance',
    (tester) async {
      final api = _Api(_overview())
        ..trainings = [
          _training(
            future: true,
            funding: 'PASS_RESERVED',
            settlement: 'OPEN',
            trainer: false,
            participant: false,
          ),
        ];
      await _pump(tester, api);
      await tester.tap(find.text('Historia'));
      await tester.pumpAndSettle();
      expect(find.text('Zaplanowany'), findsOneWidget);
      expect(find.text('Wejście zarezerwowane'), findsOneWidget);
      expect(find.text('Trener'), findsNothing);
      expect(find.text('Uczestnik'), findsNothing);
    },
  );

  testWidgets(
    'empty packages and no qualifications are distinct from a failed load',
    (tester) async {
      final api = _Api(
        _overview(passes: [], availability: 'NO_QUALIFIED_SERVICES'),
      )..fails = true;
      await _pump(tester, api);
      expect(
        find.text('Nie udało się pobrać pakietów i treningów.'),
        findsOneWidget,
      );
      expect(find.text('Brak pakietów na treningi personalne.'), findsNothing);
      api.fails = false;
      await tester.tap(find.text('Spróbuj ponownie'));
      await tester.pumpAndSettle();
      expect(
        find.text('Brak kwalifikacji do treningów personalnych'),
        findsOneWidget,
      );
      expect(api.details, 2);
    },
  );

  testWidgets('refreshes on resume and keeps the selected calendar month', (
    tester,
  ) async {
    final api = _Api(_overview());
    await _pump(tester, api);
    await tester.tap(find.text('Historia'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Miesiąc'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Następny'));
    await tester.pumpAndSettle();
    final lastWindow = api.calendarCalls.last;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(api.details, greaterThan(1));
    expect(api.calendarCalls.last, lastWindow);
  });

  testWidgets(
    'hides a late result after account changes and removes trainer data in another role',
    (tester) async {
      final late = Completer<TraineeOverviewDetails>();
      final api = _Api(_overview())..detailLoad = () => late.future;
      final container = await _pump(tester, api, settle: false);
      await tester.pump();
      final auth = container.read(authControllerProvider.notifier) as _Auth;
      api.detailLoad = null;
      api.overview = _overview(name: 'Nowy podopieczny');
      auth.replace(_session(account: 'another', staff: 'another-staff'));
      await tester.pumpAndSettle();
      late.complete(
        TraineeOverviewDetails(
          checkedOn: _checkedOn,
          checkedAt: _checkedAt,
          trainee: _overview(name: 'Dawny podopieczny'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Nowy podopieczny'), findsOneWidget);
      expect(find.text('Dawny podopieczny'), findsNothing);
      auth.replace(
        _session(
          account: 'another',
          staff: 'another-staff',
          role: AppRole.client,
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Nowy podopieczny'), findsNothing);
      expect(find.text('3 wejścia'), findsNothing);
    },
  );

  testWidgets(
    'keeps the compact card usable on a narrow screen with larger text',
    (tester) async {
      final api = _Api(
        _overview(
          next: _training(future: true, funding: 'PASS_RESERVED'),
          attention: _training(attention: true),
        ),
      );
      await _pump(tester, api, list: true, width: 320, height: 900, scale: 1.6);
      expect(find.text('3 wejścia'), findsOneWidget);
      expect(find.byType(TraineeNextTraining), findsNothing);
      expect(find.byTooltip('Najbliższy trening'), findsOneWidget);
      expect(find.textContaining('18:00').hitTestable(), findsOneWidget);
      expect(find.textContaining('19:00').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Tomasz Lewandowski'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byType(TraineeNextTraining), findsOneWidget);
      expect(find.textContaining('18:00').hitTestable(), findsOneWidget);
      await tester.tap(find.byType(TraineeNextTraining));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      expect(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text('Sala PT'),
        ),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'does not warn a package with four entries and prevents opening inactive trainees',
    (tester) async {
      final api = _Api(
        _overview(
          passes: [_pass(entries: 4)],
          active: false,
          availability: 'INACTIVE',
        ),
      );
      await _pump(tester, api, list: true, active: false);
      expect(find.byIcon(Icons.warning_amber_rounded), findsNothing);
      await tester.tap(find.text('Tomasz Lewandowski'));
      await tester.pumpAndSettle();
      expect(find.byType(TraineeOverviewScreen), findsNothing);
      expect(api.details, 0);
    },
  );

  testWidgets('shows the trainee profile in its own tab', (tester) async {
    await _pump(tester, _Api(_overview(passes: [_pass()])));
    await tester.tap(find.text('Profil'));
    await tester.pumpAndSettle();
    expect(find.text('Dane klienta'), findsOneWidget);
    expect(find.text('+48 600 100 200'), findsOneWidget);
    expect(find.text('Kontakt awaryjny'), findsOneWidget);
    expect(find.text('Anna Lewandowska'), findsOneWidget);
    expect(find.text('+48 600 300 400'), findsOneWidget);
    expect(find.text('Opiekunowie'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  test('parses the profile contract and computes full years of age', () {
    final profile = TraineeProfile.fromJson({
      'customerId': 'child',
      'displayName': 'Zosia Dąbrowska',
      'kind': 'CHILD',
      'birthDate': '2015-05-12',
      'phone': null,
      'emergencyContactName': null,
      'emergencyPhone': null,
      'guardians': [
        {
          'displayName': 'Joanna Dąbrowska',
          'phone': '+48 600 111 222',
          'relation': 'PARENT',
        },
      ],
      'assignedAt': '2026-10-01T10:00:00Z',
    });
    expect(profile.isChild, isTrue);
    expect(profile.phone, isNull);
    expect(profile.guardians.single.phone, '+48 600 111 222');
    expect(profile.ageOn(DateTime(2026, 5, 11)), 10);
    expect(profile.ageOn(DateTime(2026, 5, 12)), 11);
  });
}
