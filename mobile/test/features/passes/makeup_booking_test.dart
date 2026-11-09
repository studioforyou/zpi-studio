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
import 'package:mobile/features/passes/data/makeup_booking_api.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/passes/presentation/makeup_booking_screen.dart';
import 'package:mobile/features/passes/presentation/makeup_credits_tab.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/group_class_details_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session _session({String account = 'account', String profile = 'self'}) =>
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
  AuthState build() => Authenticated(_session());
  void replace(Session session) => state = Authenticated(session);
}

ClassReservation _reservation() => ClassReservation(
  id: 'reservation',
  eventId: 'event',
  customerId: 'self',
  status: 'ACTIVE',
  customerPassId: null,
  freeCancelUntil: DateTime.now().add(const Duration(days: 1)),
  entryReturned: null,
  cancellation: const ClassCancellation(
    allowed: true,
    reason: null,
    entryReturned: true,
  ),
  version: 0,
  makeupCreditId: 'credit',
);
GroupClass _event({bool full = false, bool booked = false}) => GroupClass(
  id: full ? 'full' : 'event',
  title: full ? 'Komplet' : 'Technika',
  serviceTypeName: 'Pole dance',
  startsAt: DateTime.now().add(const Duration(days: 2)),
  endsAt: DateTime.now().add(const Duration(days: 2, hours: 1)),
  status: 'SCHEDULED',
  capacity: 8,
  activeReservations: full ? 8 : 1,
  availablePlaces: full ? 0 : 7,
  staff: const [],
  roomNames: const ['Sala pole dance'],
  ownReservation: booked ? _reservation() : null,
  booking: ClassBooking(
    allowed: !full && !booked,
    reason: full
        ? 'CLASS_FULL'
        : booked
        ? 'ALREADY_RESERVED'
        : null,
    eligiblePasses: const [],
  ),
);
MakeupOffer _offer() => MakeupOffer(
  productName: 'Poprzedni karnet',
  expiresAt: DateTime.now().add(const Duration(days: 10)),
  items: [_event(), _event(full: true)],
);

class _Api implements MakeupBookingApi {
  Completer<ClassReservation>? pendingReserve;
  final loads = <MakeupBookingQuery>[];
  final bookings = <(MakeupBookingQuery, String)>[];
  Future<MakeupOffer> Function()? load;
  bool conflict = false;
  @override
  Future<MakeupOffer> fetch(MakeupBookingQuery query) {
    loads.add(query);
    return load?.call() ?? Future.value(_offer());
  }

  @override
  Future<ClassReservation> reserve(
    MakeupBookingQuery query,
    String eventId,
  ) async {
    bookings.add((query, eventId));
    if (pendingReserve != null) return pendingReserve!.future;
    if (conflict) {
      load = () async => throw const ClassBookingException(
        statusCode: 409,
        code: 'MAKEUP_CREDIT_NOT_AVAILABLE',
      );
      throw const ClassBookingException(
        statusCode: 409,
        code: 'MAKEUP_CREDIT_NOT_AVAILABLE',
      );
    }
    return _reservation();
  }
}

class _Credits implements MakeupCreditsApi {
  @override
  Future<MakeupCreditPage> fetch(String profileId, {int page = 0}) async =>
      MakeupCreditPage(
        items: [
          MakeupCredit(
            id: 'credit',
            productName: 'Poprzedni karnet',
            expiresAt: DateTime.now().add(const Duration(days: 10)),
          ),
        ],
        page: 0,
        totalItems: 1,
        totalPages: 1,
      );
}

class _Adapter implements HttpClientAdapter {
  RequestOptions? last;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    last = options;
    final reservation = {
      'id': 'reservation',
      'eventId': 'event',
      'customerId': 'child',
      'status': 'ACTIVE',
      'customerPassId': null,
      'makeupCreditId': 'credit',
      'freeCancelUntil': null,
      'entryReturned': null,
      'cancellation': {
        'allowed': false,
        'reason': 'EVENT_ALREADY_STARTED',
        'entryReturned': null,
      },
      'version': 0,
    };
    return ResponseBody.fromString(
      jsonEncode(
        options.method == 'POST'
            ? reservation
            : {
                'productName': 'Karnet',
                'expiresAt': '2030-01-01T12:00:00Z',
                'items': [],
              },
      ),
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
  bool home = false,
  bool settle = true,
  double width = 500,
  double scale = 1,
}) async {
  tester.view.physicalSize = Size(width, 1300);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      authControllerProvider.overrideWith(_Auth.new),
      makeupBookingApiProvider.overrideWithValue(api),
      makeupCreditsApiProvider.overrideWithValue(_Credits()),
      groupClassProvider.overrideWith(
        (ref, query) async => _event(booked: true),
      ),
      classParticipantsProvider.overrideWith((ref, query) async => []),
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
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('pl'),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: home
            ? const Scaffold(body: MakeupCreditsTab())
            : const MakeupBookingScreen(
                scope: (accountId: 'account', profileId: 'self'),
                creditId: 'credit',
              ),
      ),
    ),
  );
  if (settle) await tester.pumpAndSettle();
  return container;
}

void main() {
  testWidgets(
    'cancelling a make-up booking explains the credit return instead of a pass refund',
    (tester) async {
      await _pump(tester, _Api());
      await tester.tap(find.text('Użyj odróbki').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Użyj odróbki').last);
      await tester.pumpAndSettle();
      final l10n = AppLocalizations.of(
        tester.element(find.byType(GroupClassDetailsScreen)),
      );
      await tester.tap(find.text(l10n.classesCancel));
      await tester.pumpAndSettle();
      expect(find.text(l10n.makeupCancelReturns), findsOneWidget);
      expect(find.text(l10n.classesCancelReturnsEntry), findsNothing);
    },
  );

  testWidgets(
    'a completed request after leaving the screen does not use a disposed ref',
    (tester) async {
      final pending = Completer<ClassReservation>();
      final api = _Api()..pendingReserve = pending;
      await _pump(tester, api);
      await tester.tap(find.text('Użyj odróbki').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Użyj odróbki').last);
      await tester.pumpAndSettle();
      expect(api.bookings, hasLength(1));
      await tester.pumpWidget(const SizedBox());
      pending.complete(_reservation());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  test(
    'GET scopes the exact credit and month; POST never sends a normal pass',
    () async {
      final adapter = _Adapter();
      final api = MakeupBookingApi(Dio()..httpClientAdapter = adapter);
      final query = (
        scope: (accountId: 'account', profileId: 'child'),
        creditId: 'credit',
        month: DateTime(2026, 11),
      );
      final result = await api.fetch(query);
      expect(
        adapter.last!.path,
        '/api/v1/me/profiles/child/makeup-credits/credit/group-classes',
      );
      expect(adapter.last!.queryParameters, {
        'from': DateTime(2026, 11).toUtc().toIso8601String(),
        'to': DateTime(2026, 12).toUtc().toIso8601String(),
      });
      expect(
        result.expiresAt,
        DateTime.parse('2030-01-01T12:00:00Z').toLocal(),
      );
      final reservation = await api.reserve(query, 'event');
      expect(adapter.last!.method, 'POST');
      expect(adapter.last!.path, endsWith('/event/reservations'));
      expect(adapter.last!.data, isNull);
      expect(reservation.makeupCreditId, 'credit');
      expect(reservation.customerPassId, isNull);
    },
  );
  testWidgets('credit card opens the exact participant and credit offer', (
    tester,
  ) async {
    final api = _Api();
    await _pump(tester, api, home: true);
    await tester.tap(find.text('Wybierz zajęcia'));
    await tester.pumpAndSettle();
    expect(api.loads.single.creditId, 'credit');
    expect(api.loads.single.scope.profileId, 'self');
    expect(find.text('Technika'), findsOneWidget);
  });
  testWidgets(
    'full classes are disabled and booking requires explicit confirmation',
    (tester) async {
      final api = _Api();
      await _pump(tester, api);
      final buttons = tester
          .widgetList<FilledButton>(find.byType(FilledButton))
          .toList();
      expect(buttons.where((b) => b.onPressed == null), hasLength(1));
      await tester.tap(find.text('Użyj odróbki').first);
      await tester.pumpAndSettle();
      expect(find.text('Rozliczenie: 1 odróbka'), findsOneWidget);
      expect(api.bookings, isEmpty);
      await tester.tap(find.text('Użyj odróbki').last);
      await tester.pumpAndSettle();
      expect(api.bookings, hasLength(1));
      expect(find.text('Rezerwacja z odróbki'), findsOneWidget);
    },
  );
  testWidgets(
    'switching participants while confirming cannot book the old participants credit',
    (tester) async {
      final api = _Api();
      final container = await _pump(tester, api);
      await tester.tap(find.text('Użyj odróbki').first);
      await tester.pumpAndSettle();
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _session(profile: 'child'),
      );
      await tester.pumpAndSettle();
      expect(find.text('Rozliczenie: 1 odróbka'), findsNothing);
      expect(find.text('Użyj odróbki'), findsNothing);
      expect(api.bookings, isEmpty);
    },
  );
  testWidgets(
    'late responses after account switching cannot reveal the previous offer',
    (tester) async {
      final pending = Completer<MakeupOffer>();
      final api = _Api()..load = () => pending.future;
      final container = await _pump(tester, api, settle: false);
      (container.read(authControllerProvider.notifier) as _Auth).replace(
        _session(account: 'another'),
      );
      pending.complete(_offer());
      await tester.pumpAndSettle();
      expect(find.text('Poprzedni karnet'), findsNothing);
      expect(find.text('Technika'), findsNothing);
    },
  );
  testWidgets(
    'a credit used elsewhere is explained and not silently charged to a pass',
    (tester) async {
      final api = _Api()..conflict = true;
      await _pump(tester, api);
      await tester.tap(find.text('Użyj odróbki').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Użyj odróbki').last);
      await tester.pumpAndSettle();
      expect(
        find.text('Ta odróbka została już wykorzystana lub wygasła.'),
        findsOneWidget,
      );
      expect(api.bookings, hasLength(1));
      expect(find.text('Rezerwacja z odróbki'), findsNothing);
    },
  );
  testWidgets(
    'month navigation retains the selected credit and fits enlarged phone text',
    (tester) async {
      final api = _Api();
      await _pump(tester, api, width: 320, scale: 1.7);
      await tester.tap(find.byTooltip('Następne'));
      await tester.pumpAndSettle();
      expect(api.loads.last.creditId, 'credit');
      expect(
        api.loads.last.month,
        DateTime(DateTime.now().year, DateTime.now().month + 1),
      );
      await tester.scrollUntilVisible(
        find.text('Użyj odróbki').last,
        150,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
