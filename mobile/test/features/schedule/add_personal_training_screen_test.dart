import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/personal_training_planning_api.dart';
import 'package:mobile/features/schedule/presentation/add_personal_training_screen.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'account',
  displayName: 'Marek',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff',
  profiles: [],
  activeProfileId: null,
);

class _Auth extends AuthController {
  @override
  AuthState build() => const Authenticated(_session);
  void signOut() => state = const Unauthenticated();
}

PersonalTrainingPreview _result({
  String? reason,
  bool busy = false,
  bool multiple = false,
}) => PersonalTrainingPreview.fromJson({
  'issues': [
    if (busy) {'type': 'RESOURCE_BUSY', 'blocking': true},
  ],
  'participantReason': reason,
  'startsCooperation': true,
  'eligiblePasses': [
    if (reason != 'NO_ELIGIBLE_PASS')
      {
        'id': 'pass',
        'productName': 'Pakiet PT',
        'entriesRemaining': 4,
        'validUntil': '2027-12-31',
      },
    if (multiple)
      {
        'id': 'other',
        'productName': 'Wejście jednorazowe',
        'entriesRemaining': 1,
        'validUntil': '2027-12-31',
      },
  ],
});

class _Api implements PersonalTrainingPlanningApi {
  final created = <(PersonalTrainingPlan, String)>[];
  PersonalTrainingPreview result = _result();
  Future<PersonalTrainingPreview> Function(PersonalTrainingPlan)? answer;
  bool rejectSave = false;
  @override
  Future<List<PlanningServiceType>> services() async => const [];
  @override
  Future<PersonalTrainingPreview> preview(PersonalTrainingPlan plan) =>
      answer?.call(plan) ?? Future.value(result);
  @override
  Future<String> create(PersonalTrainingPlan plan, String passId) async {
    if (rejectSave) {
      result = _result(busy: true);
      throw const PlanRejectedException(
        code: 'STAFF_SCHEDULE_CONFLICT',
        message: 'Conflict',
        statusCode: 409,
      );
    }
    created.add((plan, passId));
    return 'new-session';
  }
}

void main() {
  late _Api api;
  late ProviderContainer container;

  Future<void> pump(
    WidgetTester tester, {
    Size size = const Size(800, 1800),
    double scale = 1,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    api = _Api();
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => const Scaffold(body: Text('Grafik')),
          routes: [
            GoRoute(
              path: 'new',
              builder: (_, _) => const AddPersonalTrainingScreen(),
            ),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(_Auth.new),
          personalTrainingPlanningApiProvider.overrideWithValue(api),
          personalPlanningServicesProvider.overrideWith(
            (_) async => const [
              PlanningServiceType(
                id: 'service',
                name: 'Trening personalny',
                defaultDurationMinutes: 60,
                maxParticipants: 1,
              ),
            ],
          ),
          planningRoomsProvider.overrideWith(
            (_) async => const [
              PlanningRoom(id: 'room', name: 'Sala treningowa', maxPeople: 10),
            ],
          ),
          customerSearchProvider.overrideWith(
            (_, query) async => const [
              CustomerSummary(
                id: 'customer',
                displayName: 'Adam Mazur',
                isChild: false,
                birthDate: null,
              ),
            ],
          ),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
        ),
      ),
    );
    container = ProviderScope.containerOf(
      tester.element(find.byType(MaterialApp)),
    );
    router.push('/new');
    await tester.pumpAndSettle();
  }

  Future<void> fill(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('personalPlanCustomer')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Adam Mazur'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('personalPlanService')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Trening personalny').last);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('personalPlanRoom')));
    await tester.tap(find.byKey(const Key('personalPlanRoom')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sala treningowa').last);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
  }

  bool canSave(WidgetTester tester) =>
      tester
          .widget<FilledButton>(find.byKey(const Key('personalPlanSave')))
          .onPressed !=
      null;

  testWidgets(
    'books the selected participant with one entry and returns to the schedule',
    (tester) async {
      await pump(tester);
      expect(canSave(tester), false);
      await fill(tester);
      expect(find.text('4 → 3'), findsOneWidget);
      expect(
        find.text('Podopieczny dołączy do Twoich podopiecznych.'),
        findsOneWidget,
      );
      expect(canSave(tester), true);
      await tester.tap(find.byKey(const Key('personalPlanSave')));
      await tester.pumpAndSettle();
      expect(api.created.single.$1.customerId, 'customer');
      expect(
        api.created.single.$1.endsAt.difference(api.created.single.$1.startsAt),
        const Duration(hours: 1),
      );
      expect(api.created.single.$2, 'pass');
      expect(find.text('Grafik'), findsOneWidget);
    },
  );

  testWidgets('several passes require an explicit choice', (tester) async {
    await pump(tester);
    api.result = _result(multiple: true);
    await fill(tester);
    expect(canSave(tester), false);
    await tester.tap(find.byKey(const ValueKey('personalPass-other')));
    await tester.pump();
    expect(canSave(tester), true);
    await tester.tap(find.byKey(const Key('personalPlanSave')));
    await tester.pumpAndSettle();
    expect(api.created.single.$2, 'other');
  });

  testWidgets('no eligible pass blocks save and explains what to do', (
    tester,
  ) async {
    await pump(tester);
    api.result = _result(reason: 'NO_ELIGIBLE_PASS');
    await fill(tester);
    expect(canSave(tester), false);
    expect(find.textContaining('Brak karnetu z wejściem'), findsOneWidget);
    expect(api.created, isEmpty);
  });

  testWidgets(
    'a concurrent booking refreshes the check and prevents another submission',
    (tester) async {
      await pump(tester);
      await fill(tester);
      api.rejectSave = true;
      await tester.tap(find.byKey(const Key('personalPlanSave')));
      await tester.pumpAndSettle();
      expect(canSave(tester), false);
      expect(
        find.textContaining('sprawdź grafik przed ponowną próbą'),
        findsOneWidget,
      );
      expect(api.created, isEmpty);
    },
  );

  testWidgets(
    'a stale preview cannot enable save while a new time is being checked',
    (tester) async {
      await pump(tester);
      await fill(tester);
      final old = Completer<PersonalTrainingPreview>();
      api.answer = (_) => old.future;
      await tester.enterText(
        find.byKey(const Key('personalPlanTime')),
        '19:00',
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.enterText(
        find.byKey(const Key('personalPlanTime')),
        '20:00',
      );
      old.complete(_result());
      await tester.pump();
      expect(canSave(tester), false);
      api.answer = (_) async => _result(busy: true);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(canSave(tester), false);
    },
  );

  testWidgets('account changes hide the form and discard a pending check', (
    tester,
  ) async {
    await pump(tester);
    await fill(tester);
    final pending = Completer<PersonalTrainingPreview>();
    api.answer = (_) => pending.future;
    await tester.enterText(find.byKey(const Key('personalPlanTime')), '19:00');
    await tester.pump(const Duration(milliseconds: 400));
    (container.read(authControllerProvider.notifier) as _Auth).signOut();
    pending.complete(_result());
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('personalPlanSave')), findsNothing);
    expect(api.created, isEmpty);
  });

  testWidgets('the form fits a small phone and larger text', (tester) async {
    await pump(tester, size: const Size(360, 740), scale: 1.3);
    await fill(tester);
    expect(tester.takeException(), isNull);
    expect(canSave(tester), true);
  });
}
