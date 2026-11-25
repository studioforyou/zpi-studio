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
import 'package:mobile/features/schedule/presentation/client/client_classes_screen.dart';
import 'package:mobile/features/schedule/data/client_schedule_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/presentation/personal_training_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

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
    addTearDown(tester.view.reset);
    final container = ProviderContainer(
      retry: (_, _) => null,
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        personalTrainingsApiProvider.overrideWithValue(api),
        groupClassesApiProvider.overrideWithValue(_Groups()),
        scheduleServiceTypesProvider.overrideWith((ref) async => const {}),
        studioTrainersProvider.overrideWith((ref) async => const []),
        scheduleChangesProvider.overrideWith((ref, owner) => Stream.value([])),
        unreadCountProvider.overrideWith(
          (ref, scope) async => UnreadNotifications.none,
        ),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          theme: buildClientTheme(Brightness.light),
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
              ? ClientClassesScreen(
                  today: DateTime(2030, 11, 5),
                  now: DateTime(2030, 11, 5, 8),
                )
              : const Scaffold(
                  body: PersonalTrainingScreen(
                    query: (scope: _scope, reservationId: 'reservation'),
                  ),
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

  Finder button(String label) => find.widgetWithText(AppButton, label);

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder.last);
    await tester.pumpAndSettle();
    await tester.tap(finder.last);
    await tester.pumpAndSettle();
  }

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
    'Moje lists group classes and personal sessions; a session opens its sheet',
    (tester) async {
      await pump(tester, _Api(), calendar: true);
      expect(find.text('Zajęcia grupowe'), findsWidgets);
      expect(find.text('Trening indywidualny'), findsWidgets);
      await tap(tester, find.text('Trening indywidualny'));
      expect(find.byType(PersonalTrainingScreen), findsOneWidget);
      expect(find.text('Pakiet PT'), findsOneWidget);
      expect(find.text('Marek'), findsWidgets);
      expect(button('Odwołaj trening'), findsOneWidget);
      expect(button('Napisz'), findsOneWidget);
    },
  );

  testWidgets(
    'cancellation shows its consequences before sending and requires confirmation',
    (tester) async {
      final api = _Api();
      await pump(tester, api);
      expect(
        find.textContaining('Możesz odwołać trening bez utraty wejścia do'),
        findsOneWidget,
      );
      await tap(tester, button('Odwołaj trening'));
      expect(find.text('Odwołać trening?'), findsOneWidget);
      expect(find.text('Odwołanie w terminie'), findsOneWidget);
      expect(find.text('+1 odróbka'), findsOneWidget);
      expect(find.text('Odróbka będzie ważna przez 30 dni.'), findsOneWidget);
      expect(api.cancellations, isEmpty);
      await tap(tester, button('Zachowaj trening'));
      expect(api.cancellations, isEmpty);
      await tap(tester, button('Odwołaj trening'));
      await tap(tester, button('Odwołaj trening'));
      expect(api.cancellations, [_scope]);
      expect(find.textContaining('Odwołane'), findsOneWidget);
      expect(button('Odwołaj trening'), findsNothing);
    },
  );

  testWidgets(
    'late cancellation warns before sending and preserves its outcome',
    (tester) async {
      final api = _Api()
        ..session = _session(effect: 'ENTRY_FORFEITED', late: true);
      await pump(tester, api);
      await tap(tester, button('Odwołaj trening'));
      expect(find.text('Wejście przepada'), findsOneWidget);
      expect(find.text('Odwołanie po terminie'), findsOneWidget);
      expect(find.textContaining('Termin minął:'), findsOneWidget);
      await tap(tester, button('Odwołaj trening'));
      expect(api.cancellations, [_scope]);
      expect(find.textContaining('Wejście przepadło'), findsOneWidget);
    },
  );

  testWidgets('family view keeps both participants and narrows to one person', (
    tester,
  ) async {
    final api = _Api()..family = true;
    await pump(tester, api, calendar: true);
    await tap(tester, find.text('Wszyscy'));
    expect(api.reads, containsAll(['self', 'child']));
    expect(find.text('Trening Zosi'), findsOneWidget);
    expect(find.text('Trening indywidualny'), findsOneWidget);
    expect(find.text('Zosia'), findsWidgets);
    expect(find.text('Monika'), findsWidgets);
    await tap(tester, find.text('Zosia').first);
    expect(find.text('Trening Zosi'), findsOneWidget);
    expect(find.text('Trening indywidualny'), findsNothing);
  });

  testWidgets(
    'a child action from the family view never cancels the parent reservation',
    (tester) async {
      final api = _Api()..family = true;
      await pump(tester, api, calendar: true);
      await tap(tester, find.text('Wszyscy'));
      await tap(tester, find.text('Trening Zosi'));
      expect(api.detailScopes.last, (accountId: 'account', profileId: 'child'));
      await tap(tester, button('Odwołaj trening'));
      await tap(tester, button('Odwołaj trening'));
      expect(api.cancellations, [(accountId: 'account', profileId: 'child')]);
      expect(api.session.status, EventStatus.scheduled);
    },
  );

  testWidgets('hiding linked profiles limits the view to the active person', (
    tester,
  ) async {
    final api = _Api()..family = true;
    final container = await pump(tester, api, calendar: true);
    await tap(tester, find.text('Wszyscy'));
    expect(find.text('Trening Zosi'), findsOneWidget);
    await container
        .read(profileViewPreferencesProvider(_scope).notifier)
        .setShowRelated(false);
    await tester.pumpAndSettle();
    expect(find.text('Wszyscy'), findsNothing);
    expect(find.text('Trening Zosi'), findsNothing);
    expect(find.text('Trening indywidualny'), findsWidgets);
    // Another active profile uses that person's own setting.
    (container.read(authControllerProvider.notifier) as _Auth).replace(
      _account(profile: 'child'),
    );
    await tester.pumpAndSettle();
    expect(find.text('Wszyscy'), findsOneWidget);
  });

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
    await tap(tester, button('Odwołaj trening'));
    expect(find.text('Bez zmiany liczby wejść'), findsOneWidget);
    expect(find.text('+1 odróbka'), findsNothing);
  });

  testWidgets(
    'a participant switch while confirming prevents cancelling the previous profile',
    (tester) async {
      final api = _Api();
      final container = await pump(tester, api);
      await tap(tester, button('Odwołaj trening'));
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _account(profile: 'child'),
      );
      await tester.pumpAndSettle();
      expect(find.text('+1 odróbka'), findsNothing);
      expect(button('Odwołaj trening'), findsNothing);
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

  testWidgets('a held session shows attendance and the dispute deadline', (
    tester,
  ) async {
    await pump(tester, _Api()..session = _session(held: true));
    expect(find.text('Odbyty'), findsOneWidget);
    expect(find.text('Obecność'), findsOneWidget);
    expect(
      find.textContaining('Coś się nie zgadza? Możesz zgłosić niezgodność'),
      findsOneWidget,
    );
    expect(button('Odwołaj trening'), findsNothing);
  });

  testWidgets(
    'a dispute requires a reason and leaves a pending review instead of changing the package',
    (tester) async {
      final api = _Api()..session = _session(held: true);
      await pump(tester, api);
      await tap(tester, button('Zgłoś niezgodność'));
      await tap(tester, button('Wyślij zgłoszenie'));
      expect(find.text('Opisz krótko niezgodność.'), findsOneWidget);
      expect(api.disputes, isEmpty);
      await tester.enterText(find.byType(TextField), 'Nie było treningu.');
      await tap(tester, button('Wyślij zgłoszenie'));
      expect(api.disputes, ['Nie było treningu.']);
      expect(
        find.text(
          'Administrator sprawdzi zgłoszenie. Saldo pakietu pozostaje bez zmian.',
        ),
        findsOneWidget,
      );
      expect(button('Zgłoś niezgodność'), findsNothing);
    },
  );

  testWidgets(
    'pending cancellation completing after leaving the screen does not use a disposed ref',
    (tester) async {
      final api = _Api()..pendingCancel = Completer<PersonalSession>();
      await pump(tester, api);
      await tap(tester, button('Odwołaj trening'));
      await tester.tap(button('Odwołaj trening').last);
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
    await tap(tester, button('Odwołaj trening'));
    expect(find.text('+1 odróbka'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
