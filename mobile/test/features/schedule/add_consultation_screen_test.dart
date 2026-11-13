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
import 'package:mobile/features/schedule/data/consultation_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/add_consultation_screen.dart';
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

PlanPreview _preview(ConsultationPlan plan, {bool busy = false}) => PlanPreview(
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

class _Api implements ConsultationApi {
  final previews = <ConsultationPlan>[];
  final created = <ConsultationPlan>[];
  Future<PlanPreview> Function(ConsultationPlan)? answer;
  bool busy = false;
  bool rejectSave = false;

  @override
  Future<PlanPreview> preview(ConsultationPlan plan) async {
    previews.add(plan);
    return answer == null ? _preview(plan, busy: busy) : answer!(plan);
  }

  @override
  Future<ScheduleEvent> create(ConsultationPlan plan) async {
    if (rejectSave) {
      busy = true;
      throw const PlanRejectedException(
        code: 'RESOURCE_CAPACITY_EXCEEDED',
        message: 'Busy',
        statusCode: 409,
      );
    }
    created.add(plan);
    return ScheduleEvent(
      id: 'new',
      kind: EventKind.consultation,
      status: EventStatus.scheduled,
      title: 'Konsultacja',
      guestName: plan.guestName,
      serviceTypeName: null,
      startsAt: plan.startsAt,
      endsAt: plan.endsAt,
      capacity: null,
      staff: const [],
      roomNames: const ['Sala'],
    );
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
              builder: (_, _) =>
                  AddConsultationScreen(initialDay: DateTime(2027, 1, 12)),
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
          consultationApiProvider.overrideWithValue(api),
          planningRoomsProvider.overrideWith(
            (_) async => const [
              PlanningRoom(id: 'room', name: 'Sala', maxPeople: 8),
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

  Future<void> selectRoom(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('consultationRoom')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sala').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
  }

  Future<void> checked(WidgetTester tester) async {
    await tester.pump(const Duration(milliseconds: 401));
    await tester.pumpAndSettle();
  }

  bool canSave(WidgetTester tester) =>
      tester
          .widget<FilledButton>(find.byKey(const Key('consultationSave')))
          .onPressed !=
      null;

  testWidgets('saves a consultation without selecting a customer or pass', (
    tester,
  ) async {
    await pump(tester);
    expect(canSave(tester), false);
    await tester.enterText(
      find.byKey(const Key('consultationGuest')),
      'Jan Gość',
    );
    await selectRoom(tester);
    await checked(tester);
    expect(find.text('Termin jest wolny.'), findsOneWidget);
    await tester.tap(find.byKey(const Key('consultationSave')));
    await tester.pumpAndSettle();
    expect(api.created.single.guestName, 'Jan Gość');
    expect(
      api.created.single.endsAt.difference(api.created.single.startsAt),
      const Duration(hours: 1),
    );
    expect(find.text('Grafik'), findsOneWidget);
  });

  testWidgets(
    'a conflict blocks save and concurrent booking refreshes the preview',
    (tester) async {
      await pump(tester);
      api.busy = true;
      await selectRoom(tester);
      await checked(tester);
      expect(canSave(tester), false);
      api.busy = false;
      await tester.enterText(
        find.byKey(const Key('consultationTime')),
        '19:00',
      );
      await checked(tester);
      expect(canSave(tester), true);
      api.rejectSave = true;
      await tester.tap(find.byKey(const Key('consultationSave')));
      await tester.pumpAndSettle();
      expect(canSave(tester), false);
      expect(api.created, isEmpty);
      expect(find.textContaining('międzyczasie'), findsOneWidget);
    },
  );

  testWidgets('old availability cannot enable save after a time change', (
    tester,
  ) async {
    await pump(tester);
    final old = Completer<PlanPreview>();
    api.answer = (_) => old.future;
    await selectRoom(tester);
    await tester.pump(const Duration(milliseconds: 401));
    final oldPlan = api.previews.single;
    await tester.enterText(find.byKey(const Key('consultationTime')), '20:00');
    old.complete(_preview(oldPlan));
    await tester.pump();
    expect(canSave(tester), false);
    api.answer = (plan) async => _preview(plan, busy: true);
    await checked(tester);
    expect(canSave(tester), false);
  });

  testWidgets('signing out discards a pending preview', (tester) async {
    await pump(tester);
    final pending = Completer<PlanPreview>();
    api.answer = (_) => pending.future;
    await selectRoom(tester);
    await tester.pump(const Duration(milliseconds: 401));
    (container.read(authControllerProvider.notifier) as _Auth).signOutForTest();
    pending.complete(_preview(api.previews.single));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('consultationSave')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('form fits a small screen with larger text', (tester) async {
    await pump(tester, size: const Size(320, 640), scale: 1.5);
    await selectRoom(tester);
    await checked(tester);
    await tester.ensureVisible(find.byKey(const Key('consultationSave')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  test('request contains room and UTC time without account or pass fields', () {
    final start = DateTime.parse('2027-01-12T18:00:00+01:00');
    final json = ConsultationPlan(
      roomId: 'room',
      startsAt: start,
      endsAt: start.add(const Duration(hours: 1)),
      guestName: '  Jan  ',
    ).toJson();
    expect(json, {
      'resourceId': 'room',
      'startsAt': '2027-01-12T17:00:00.000Z',
      'endsAt': '2027-01-12T18:00:00.000Z',
      'guestName': 'Jan',
    });
  });
}
