import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/profile_view_preferences.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_screen.dart';
import 'package:mobile/features/schedule/data/client_schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/presentation/personal_training_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _scope = (accountId: 'account', profileId: 'self');
Session _account({String account = 'account', String profile = 'self'}) =>
    Session(
      accountId: account,
      displayName: 'Monika',
      roles: const [AppRole.client],
      activeRole: AppRole.client,
      staffProfileId: null,
      profiles: const [
        Profile(id: 'self', displayName: 'Monika', isChild: false),
        Profile(id: 'child', displayName: 'Zosia', isChild: true),
      ],
      activeProfileId: profile,
    );

class _Auth extends AuthController {
  @override
  AuthState build() => Authenticated(_account());
  void replace(Session session) => state = Authenticated(session);
}

PersonalSession _session({
  String effect = 'MAKEUP_GRANTED',
  bool held = false,
  bool disputed = false,
  bool cancelled = false,
  String profile = 'self',
  bool late = false,
}) => PersonalSession(
  id: 'event',
  reservationId: profile == 'self' ? 'reservation' : 'reservation-child',
  status: cancelled
      ? EventStatus.cancelled
      : held
      ? EventStatus.completed
      : EventStatus.scheduled,
  title: profile == 'self' ? 'Trening indywidualny' : 'Trening Zosi',
  serviceTypeName: 'Personalny',
  startsAt: DateTime(2030, 11, 5, 18),
  endsAt: DateTime(2030, 11, 5, 19),
  staff: const [
    EventStaff(staffId: 'trainer', displayName: 'Marek', isLead: true),
  ],
  roomNames: const ['Sala'],
  version: 4,
  passName: 'Pakiet PT',
  attendance: held ? 'PRESENT' : 'UNMARKED',
  trainerConfirmedAt: held ? DateTime(2030, 11, 5, 19) : null,
  settlementStatus: disputed ? 'DISPUTED' : 'OPEN',
  cancellationEffect: cancelled ? effect : null,
  cancellation: PersonalCancellation(
    allowed: !held && !cancelled,
    late: late,
    effect: effect,
    validityDays: effect == 'MAKEUP_GRANTED' ? 30 : null,
    freeCancelUntil: DateTime(2030, 11, 4, 18),
  ),
  canDispute: held && !disputed,
  disputeClosesAt: DateTime(2030, 11, 6, 19),
  dispute: disputed
      ? const PersonalDispute(status: 'OPEN', reason: 'Nie było treningu.')
      : null,
);

class _Api extends PersonalTrainingsApi {
  _Api() : super(Dio());
  PersonalSession session = _session();
  PersonalSession childSession = _session(profile: 'child');
  bool family = false;
  final reads = <String>[];
  final detailScopes = <BookingScope>[];
  Completer<PersonalSession>? pendingGet;
  Completer<PersonalSession>? pendingCancel;
  final cancellations = <BookingScope>[];
  final disputes = <String>[];
  @override
  Future<List<PersonalSession>> fetch(ClassesQuery query) async {
    reads.add(query.scope.profileId);
    return [
      family && query.scope.profileId == 'child' ? childSession : session,
    ];
  }

  @override
  Future<PersonalSession> get(PersonalSessionQuery query) {
    detailScopes.add(query.scope);
    return pendingGet?.future ??
        Future.value(
          family && query.scope.profileId == 'child' ? childSession : session,
        );
  }

  @override
  Future<PersonalSession> cancel(
    BookingScope scope,
    PersonalSession value,
  ) async {
    cancellations.add(scope);
    if (pendingCancel != null) return pendingCancel!.future;
    final result = _session(
      effect: value.cancellation.effect!,
      cancelled: true,
      profile: scope.profileId,
    );
    if (family && scope.profileId == 'child') {
      return childSession = result;
    }
    return session = result;
  }

  @override
  Future<PersonalSession> dispute(
    BookingScope scope,
    PersonalSession value,
    String reason,
  ) async {
    disputes.add(reason);
    return session = _session(held: true, disputed: true);
  }
}

class _Groups extends GroupClassesApi {
  _Groups() : super(Dio());
  @override
  Future<List<GroupClass>> fetchReservations(ClassesQuery query) async => [
    GroupClass(
      id: 'group',
      title: 'Zajęcia grupowe',
      serviceTypeName: 'Pole dance',
      startsAt: DateTime(2030, 11, 5, 16),
      endsAt: DateTime(2030, 11, 5, 17),
      status: 'SCHEDULED',
      capacity: 8,
      activeReservations: 2,
      availablePlaces: 6,
      staff: const [],
      roomNames: const [],
      ownReservation: null,
      booking: const ClassBooking(
        allowed: false,
        reason: null,
        eligiblePasses: [],
      ),
    ),
  ];
}

class _Adapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancel,
  ) async {
    requests.add(options);
    final data = {
      'id': 'event',
      'reservationId': 'reservation',
      'kind': 'PERSONAL_TRAINING',
      'status': 'SCHEDULED',
      'title': 'Trening',
      'serviceTypeName': 'PT',
      'startsAt': '2030-11-05T18:00:00Z',
      'endsAt': '2030-11-05T19:00:00Z',
      'staff': [],
      'resources': [],
      'version': 4,
      'attendance': 'UNMARKED',
      'settlementStatus': 'OPEN',
      'cancellation': {
        'allowed': true,
        'late': false,
        'effect': 'MAKEUP_GRANTED',
        'validityDays': 30,
      },
      'disputeAvailability': {'allowed': false},
    };
    return ResponseBody.fromString(
      jsonEncode(
        options.method == 'GET' && !options.path.endsWith('/reservation')
            ? {
                'items': [data],
              }
            : data,
      ),
      200,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  Future<ProviderContainer> pump(
    WidgetTester tester,
    _Api api, {
    bool calendar = false,
    bool large = false,
  }) async {
    tester.view.physicalSize = large
        ? const Size(360, 700)
        : const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final container = ProviderContainer(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        personalTrainingsApiProvider.overrideWithValue(api),
        groupClassesApiProvider.overrideWithValue(_Groups()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          locale: const Locale('pl'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: large
              ? (context, child) => MediaQuery(
                  data: MediaQuery.of(
                    context,
                  ).copyWith(textScaler: const TextScaler.linear(1.8)),
                  child: child!,
                )
              : null,
          home: calendar
              ? ClientScheduleScreen(
                  reservationsOnly: true,
                  today: DateTime(2030, 11, 5),
                )
              : const PersonalTrainingScreen(
                  query: (scope: _scope, reservationId: 'reservation'),
                ),
        ),
      ),
    );
    if (api.pendingGet == null) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
    }
    return container;
  }

  Finder dialogButton(String label) => find.descendant(
    of: find.byType(AlertDialog),
    matching: find.widgetWithText(FilledButton, label),
  );

  test(
    'API scopes the participant and submits the exact version and confirmed consequence',
    () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://example.test'));
      final adapter = _Adapter();
      dio.httpClientAdapter = adapter;
      final api = PersonalTrainingsApi(dio);
      const scope = (accountId: 'account', profileId: 'child');
      final sessions = await api.fetch((
        scope: scope,
        window: (from: DateTime(2030, 11, 1), to: DateTime(2030, 12, 1)),
      ));
      await api.cancel(scope, sessions.single);
      expect(
        adapter.requests.last.path,
        '/api/v1/me/profiles/child/personal-trainings/reservation/cancel',
      );
      expect(adapter.requests.last.data, {
        'expectedVersion': 4,
        'expectedEffect': 'MAKEUP_GRANTED',
        'expectedValidityDays': 30,
      });
      await api.dispute(scope, sessions.single, 'Nie było treningu.');
      expect(adapter.requests.last.data, {
        'expectedVersion': 4,
        'reason': 'Nie było treningu.',
      });
    },
  );

  testWidgets(
    'one calendar contains both group classes and reserved personal sessions',
    (tester) async {
      await pump(tester, _Api(), calendar: true);
      expect(find.textContaining('Zajęcia grupowe'), findsOneWidget);
      expect(find.textContaining('Trening indywidualny'), findsOneWidget);
      expect(find.text('18:00–19:00'), findsOneWidget);
      expect(find.text('Marek'), findsOneWidget);
      expect(find.text('Sala'), findsOneWidget);
      expect(find.text('Zaplanowany'), findsOneWidget);
      await tester.tap(find.textContaining('Trening indywidualny'));
      await tester.pumpAndSettle();
      expect(find.text('Pakiet PT'), findsOneWidget);
      expect(find.text('Odwołaj trening'), findsOneWidget);
    },
  );

  testWidgets(
    'cancellation shows its consequence before sending and requires confirmation',
    (tester) async {
      final api = _Api();
      await pump(tester, api);
      await tester.tap(find.text('Odwołaj trening'));
      await tester.pumpAndSettle();
      expect(find.text('+1 odróbka'), findsOneWidget);
      expect(api.cancellations, isEmpty);
      await tester.tap(find.text('Zachowaj rezerwację'));
      await tester.pumpAndSettle();
      expect(api.cancellations, isEmpty);
      await tester.tap(find.text('Odwołaj trening'));
      await tester.pumpAndSettle();
      await tester.tap(dialogButton('Odwołaj trening'));
      await tester.pumpAndSettle();
      expect(api.cancellations, [_scope]);
      expect(find.text('Odwołane'), findsOneWidget);
    },
  );

  testWidgets(
    'late cancellation warns before sending and preserves its outcome',
    (tester) async {
      final api = _Api()
        ..session = _session(effect: 'ENTRY_FORFEITED', late: true);
      await pump(tester, api);
      await tester.tap(find.text('Odwołaj trening'));
      await tester.pumpAndSettle();
      expect(find.text('Wejście przepada'), findsOneWidget);
      expect(find.textContaining('Termin minął:'), findsNWidgets(2));
      expect(api.cancellations, isEmpty);
      await tester.tap(dialogButton('Odwołaj trening'));
      await tester.pumpAndSettle();
      expect(api.cancellations, [_scope]);
      expect(find.text('Wejście przepadło'), findsOneWidget);
      expect(find.text('Odwołane'), findsOneWidget);
    },
  );

  testWidgets(
    'family schedule keeps both participants even in the same group event',
    (tester) async {
      final api = _Api()..family = true;
      await pump(tester, api, calendar: true);
      expect(find.byType(DropdownButtonFormField<String>), findsNothing);
      await tester.tap(find.text('Wszyscy'));
      await tester.pumpAndSettle();
      expect(api.reads, containsAll(['self', 'child']));
      expect(find.textContaining('Trening Zosi'), findsOneWidget);
      expect(find.textContaining('Trening indywidualny'), findsOneWidget);
      expect(find.text('Zajęcia grupowe'), findsNWidgets(2));
      expect(find.text('Monika'), findsAtLeastNWidgets(2));
      expect(find.text('Zosia'), findsAtLeastNWidgets(2));
      await tester.tap(find.byKey(const ValueKey('schedule-profile-child')));
      await tester.pumpAndSettle();
      expect(find.textContaining('Trening Zosi'), findsOneWidget);
      expect(find.textContaining('Trening indywidualny'), findsNothing);
      expect(find.text('Zajęcia grupowe'), findsOneWidget);
    },
  );

  testWidgets(
    'a child action from the combined schedule never cancels the parent reservation',
    (tester) async {
      final api = _Api()..family = true;
      await pump(tester, api, calendar: true);
      await tester.tap(find.text('Wszyscy'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.textContaining('Trening Zosi'));
      await tester.tap(find.textContaining('Trening Zosi'));
      await tester.pumpAndSettle();
      expect(api.detailScopes.last, (accountId: 'account', profileId: 'child'));
      expect(find.text('Zosia'), findsOneWidget);
      await tester.tap(find.text('Odwołaj trening'));
      await tester.pumpAndSettle();
      await tester.tap(dialogButton('Odwołaj trening'));
      await tester.pumpAndSettle();
      expect(api.cancellations, [(accountId: 'account', profileId: 'child')]);
      expect(api.session.status, EventStatus.scheduled);
      Navigator.of(tester.element(find.byType(PersonalTrainingScreen))).pop();
      await tester.pumpAndSettle();
      expect(find.textContaining('Trening Zosi'), findsOneWidget);
      expect(find.textContaining('Trening indywidualny'), findsOneWidget);
    },
  );

  testWidgets(
    'family choices remain usable on a narrow screen with large text',
    (tester) async {
      await pump(tester, _Api()..family = true, calendar: true, large: true);
      await tester.tap(find.text('Wszyscy'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byTooltip('Grafik: Monika'), findsOneWidget);
      expect(find.byTooltip('Grafik: Zosia'), findsOneWidget);
    },
  );

  testWidgets(
    'hiding linked profiles immediately limits a combined calendar to the active person',
    (tester) async {
      final api = _Api()..family = true;
      final container = await pump(tester, api, calendar: true);
      await tester.tap(find.text('Wszyscy'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Trening Zosi'), findsOneWidget);
      await container
          .read(profileViewPreferencesProvider(_scope).notifier)
          .setShowRelated(false);
      await tester.pumpAndSettle();
      expect(find.text('Wszyscy'), findsNothing);
      expect(
        find.byKey(const ValueKey('schedule-profile-child')),
        findsNothing,
      );
      expect(find.textContaining('Trening Zosi'), findsNothing);
      expect(find.textContaining('Trening indywidualny'), findsOneWidget);
      // Changing the active profile elsewhere still works and uses that person's own setting.
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _account(profile: 'child'),
      );
      await tester.pumpAndSettle();
      expect(find.text('Wszyscy'), findsOneWidget);
    },
  );

  testWidgets(
    'switching family profiles preserves the month and selected view',
    (tester) async {
      final api = _Api()..family = true;
      await pump(tester, api, calendar: true);
      await tester.tap(find.text('Miesiąc'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Następny'));
      await tester.pumpAndSettle();
      expect(find.text('Grudzień 2030'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('schedule-profile-child')));
      await tester.pumpAndSettle();
      expect(find.text('Grudzień 2030'), findsOneWidget);
      expect(
        tester
            .widget<SegmentedButton<ScheduleView>>(
              find.byType(SegmentedButton<ScheduleView>),
            )
            .selected,
        {ScheduleView.month},
      );
      await tester.tap(find.text('Wszyscy'));
      await tester.pumpAndSettle();
      expect(find.text('Grudzień 2030'), findsOneWidget);
    },
  );

  test('family data cannot be reused by another account', () async {
    final api = _Api()..family = true;
    final container = ProviderContainer(
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        personalTrainingsApiProvider.overrideWithValue(api),
        groupClassesApiProvider.overrideWithValue(_Groups()),
      ],
      retry: (_, _) => null,
    );
    addTearDown(container.dispose);
    final query = (
      accountId: 'account',
      window: (from: DateTime(2030, 11, 1), to: DateTime(2030, 12, 1)),
    );
    final subscription = container.listen(
      familyScheduleProvider(query),
      (_, _) {},
    );
    addTearDown(subscription.close);
    expect(
      await container.read(familyScheduleProvider(query).future),
      hasLength(4),
    );
    (container.read(authControllerProvider.notifier) as _Auth).replace(
      _account(account: 'other'),
    );
    await expectLater(
      container.read(familyScheduleProvider(query).future),
      throwsA(isA<ClassBookingException>()),
    );
  });

  testWidgets('unlimited sessions show no extra makeup credit', (tester) async {
    final api = _Api()..session = _session(effect: 'NO_ENTRY');
    await pump(tester, api);
    await tester.tap(find.text('Odwołaj trening'));
    await tester.pumpAndSettle();
    expect(find.text('Bez zmiany liczby wejść'), findsOneWidget);
    expect(find.text('+1 odróbka'), findsNothing);
  });

  testWidgets(
    'a participant switch while confirming prevents cancellation of the previous profile',
    (tester) async {
      final api = _Api();
      final container = await pump(tester, api);
      await tester.tap(find.text('Odwołaj trening'));
      await tester.pumpAndSettle();
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _account(profile: 'child'),
      );
      await tester.pumpAndSettle();
      expect(dialogButton('Odwołaj trening'), findsNothing);
      expect(find.text('+1 odróbka'), findsNothing);
      expect(api.cancellations, isEmpty);
    },
  );

  testWidgets(
    'a late result after switching accounts cannot show the previous session',
    (tester) async {
      final api = _Api()..pendingGet = Completer<PersonalSession>();
      final container = await pump(tester, api);
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _account(account: 'other'),
      );
      api.pendingGet!.complete(_session());
      await tester.pumpAndSettle();
      expect(find.text('Trening indywidualny'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'a dispute requires a reason and leaves a pending review instead of changing the package',
    (tester) async {
      final api = _Api()..session = _session(held: true);
      await pump(tester, api);
      await tester.tap(find.text('Zgłoś niezgodność'));
      await tester.pumpAndSettle();
      await tester.tap(dialogButton('Wyślij zgłoszenie'));
      await tester.pumpAndSettle();
      expect(find.text('Opisz krótko niezgodność.'), findsOneWidget);
      expect(api.disputes, isEmpty);
      await tester.enterText(find.byType(TextField), 'Nie było treningu.');
      await tester.tap(dialogButton('Wyślij zgłoszenie'));
      await tester.pumpAndSettle();
      expect(api.disputes, ['Nie było treningu.']);
      expect(
        find.text(
          'Administrator sprawdzi zgłoszenie. Saldo pakietu pozostaje bez zmian.',
        ),
        findsOneWidget,
      );
      expect(find.text('Zgłoś niezgodność'), findsNothing);
    },
  );

  testWidgets(
    'pending cancellation completing after leaving the screen does not use a disposed ref',
    (tester) async {
      final api = _Api()..pendingCancel = Completer<PersonalSession>();
      await pump(tester, api);
      await tester.tap(find.text('Odwołaj trening'));
      await tester.pumpAndSettle();
      await tester.tap(dialogButton('Odwołaj trening'));
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      api.pendingCancel!.complete(_session(cancelled: true));
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('the confirmation fits a narrow screen with enlarged text', (
    tester,
  ) async {
    await pump(tester, _Api(), large: true);
    await tester.scrollUntilVisible(
      find.text('Odwołaj trening'),
      120,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Odwołaj trening'));
    await tester.pumpAndSettle();
    expect(find.text('+1 odróbka'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
