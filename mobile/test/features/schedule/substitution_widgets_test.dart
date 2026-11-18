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
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/substitution_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/substitution_widgets.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'account',
  displayName: 'Anna',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff',
  profiles: [],
  activeProfileId: null,
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
  void signOutForTest() => state = const Unauthenticated();
}

SubstitutionRequest _request({bool claimed = false}) => SubstitutionRequest(
  id: 'request',
  eventId: 'event',
  status: claimed ? 'CLAIMED' : 'OPEN',
  reason: 'Wyjazd',
  createdAt: DateTime(2026, 11, 13),
  canCancel: !claimed,
);

ScheduleEvent _event({
  bool lead = true,
  EventStatus status = EventStatus.scheduled,
  DateTime? startsAt,
}) => ScheduleEvent(
  id: 'event',
  kind: EventKind.groupClass,
  status: status,
  title: 'Pole dance',
  serviceTypeName: 'Pole dance',
  startsAt: startsAt ?? DateTime(2099, 1, 8, 18),
  endsAt: DateTime(2099, 1, 8, 19),
  capacity: 10,
  staff: [EventStaff(staffId: 'staff', displayName: 'Anna', isLead: lead)],
  roomNames: const ['Sala'],
);

class _Api implements SubstitutionApi {
  SubstitutionRequest? request;
  Object? failure;
  Object? loadFailure;
  Completer<void>? pending;
  final created = <(String, String)>[];
  int cancelled = 0;
  int stateCalls = 0;
  @override
  Future<SubstitutionState> fetchState(String eventId) async {
    stateCalls++;
    if (loadFailure case final error?) throw error;
    return SubstitutionState(canRequest: request == null, request: request);
  }

  @override
  Future<List<SubstitutionRequest>> fetchOwn(ScheduleWindow window) async => [
    ?request,
  ];
  @override
  Future<void> create(String eventId, String reason) async {
    created.add((eventId, reason));
    await pending?.future;
    if (failure case final error?) throw error;
    request = _request();
  }

  @override
  Future<void> cancel(String requestId) async {
    cancelled++;
    request = null;
  }
}

class _Adapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  int status = 200;
  Object body = const {};
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
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

void main() {
  late _Api api;
  late ProviderContainer container;
  Future<void> pump(
    WidgetTester tester, {
    ScheduleEvent? event,
    Size size = const Size(800, 1600),
    double scale = 1,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(_Auth.new),
          substitutionApiProvider.overrideWithValue(api),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: Scaffold(
            body: SingleChildScrollView(
              child: OwnSubstitutionSection(event: event ?? _event()),
            ),
          ),
        ),
      ),
    );
    container = ProviderScope.containerOf(
      tester.element(find.byType(MaterialApp)),
    );
    await tester.pumpAndSettle();
  }

  setUp(() {
    api = _Api();
  });

  Future<void> openForm(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('requestSubstitution')));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'creates an optional-reason request and shows the pending status',
    (tester) async {
      await pump(tester);
      await openForm(tester);
      await tester.enterText(
        find.byKey(const Key('substitutionReason')),
        'Wyjazd',
      );
      await tester.tap(find.byKey(const Key('sendSubstitution')));
      await tester.pumpAndSettle();
      expect(api.created.single, ('event', 'Wyjazd'));
      expect(find.text('Szuka zastępstwa'), findsOneWidget);
      expect(find.byKey(const Key('requestSubstitution')), findsNothing);
    },
  );

  testWidgets(
    'withdrawal requires confirmation then enables requesting again',
    (tester) async {
      api.request = _request();
      await pump(tester);
      await tester.tap(find.byKey(const Key('withdrawSubstitution')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Zostaw zgłoszenie'));
      await tester.pumpAndSettle();
      expect(api.cancelled, 0);
      await tester.tap(find.byKey(const Key('withdrawSubstitution')));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, 'Wycofaj').last);
      await tester.pumpAndSettle();
      expect(api.cancelled, 1);
      expect(find.byKey(const Key('requestSubstitution')), findsOneWidget);
    },
  );

  testWidgets(
    'a claimed request cannot be withdrawn and its reason opens separately',
    (tester) async {
      api.request = _request(claimed: true);
      await pump(tester);
      expect(find.text('Zastępca zgłoszony'), findsOneWidget);
      expect(find.byKey(const Key('withdrawSubstitution')), findsNothing);
      expect(find.text('Wyjazd'), findsNothing);
      await tester.tap(find.byTooltip('Powód zgłoszenia'));
      await tester.pumpAndSettle();
      expect(find.text('Wyjazd'), findsOneWidget);
    },
  );

  testWidgets('network failure retains the draft and permits retry', (
    tester,
  ) async {
    api.failure = const SubstitutionException(message: 'offline');
    await pump(tester);
    await openForm(tester);
    await tester.enterText(
      find.byKey(const Key('substitutionReason')),
      'Wyjazd',
    );
    await tester.tap(find.byKey(const Key('sendSubstitution')));
    await tester.pumpAndSettle();
    expect(
      find.text('Brak połączenia z serwerem. Spróbuj ponownie.'),
      findsOneWidget,
    );
    expect(find.text('Wyjazd'), findsOneWidget);
    api.failure = null;
    await tester.tap(find.byKey(const Key('sendSubstitution')));
    await tester.pumpAndSettle();
    expect(find.text('Szuka zastępstwa'), findsOneWidget);
  });

  testWidgets('does not send twice and discards the result after signing out', (
    tester,
  ) async {
    await pump(tester);
    await openForm(tester);
    api.pending = Completer<void>();
    await tester.tap(find.byKey(const Key('sendSubstitution')));
    await tester.pump();
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('sendSubstitution')))
          .onPressed,
      isNull,
    );
    (container.read(authControllerProvider.notifier) as _Auth).signOutForTest();
    api.pending!.complete();
    await tester.pumpAndSettle();
    expect(api.created, hasLength(1));
    expect(find.text('Szuka zastępstwa'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'does not offer requesting to an assistant or a past or cancelled event',
    (tester) async {
      await pump(tester, event: _event(lead: false));
      expect(api.stateCalls, 0);
      await tester.pumpWidget(const SizedBox());
      await pump(tester, event: _event(startsAt: DateTime(2020)));
      expect(api.stateCalls, 0);
      await tester.pumpWidget(const SizedBox());
      await pump(tester, event: _event(status: EventStatus.cancelled));
      expect(api.stateCalls, 0);
    },
  );

  testWidgets(
    'personal training uses the event id rather than the reservation id',
    (tester) async {
      final training = TraineeTraining(
        event: _event(),
        reservationId: 'reservation',
        trainerConfirmedAt: null,
        clientConfirmedAt: null,
        attendance: 'UNMARKED',
        settlementStatus: 'PENDING',
        funding: 'PASS',
        passId: null,
        passName: null,
        requiresAttention: false,
        customerName: 'Tomasz',
      );
      await pump(tester, event: training);
      await openForm(tester);
      await tester.tap(find.byKey(const Key('sendSubstitution')));
      await tester.pumpAndSettle();
      expect(api.created.single.$1, 'event');
    },
  );

  testWidgets('request form and status fit a small screen with larger text', (
    tester,
  ) async {
    await pump(tester, size: const Size(320, 640), scale: 1.5);
    await openForm(tester);
    await tester.ensureVisible(find.byKey(const Key('sendSubstitution')));
    await tester.tap(find.byKey(const Key('sendSubstitution')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  test(
    'API sends scoped UTC windows and trimmed optional reasons without a staff id',
    () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://studio.test'));
      final adapter = _Adapter();
      dio.httpClientAdapter = adapter;
      final api = SubstitutionApi(dio);
      adapter.body = {'items': []};
      final from = DateTime.parse('2026-11-13T00:00:00+01:00');
      await api.fetchOwn((from: from, to: from.add(const Duration(days: 7))));
      expect(
        adapter.requests.single.queryParameters['from'],
        '2026-11-12T23:00:00.000Z',
      );
      await api.create('event', '  Wyjazd  ');
      expect(
        adapter.requests.last.path,
        '/api/v1/me/schedule-events/event/substitution-request',
      );
      expect(adapter.requests.last.data, {'reason': 'Wyjazd'});
      await api.create('event', '   ');
      expect(adapter.requests.last.data, {'reason': null});
    },
  );
}
