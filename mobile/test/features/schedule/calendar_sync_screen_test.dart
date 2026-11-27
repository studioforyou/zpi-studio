import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';
import 'package:mobile/features/schedule/presentation/calendar_sync_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session _session(List<AppRole> roles) => Session(
  accountId: 'account-1',
  displayName: 'Anna Kowalska',
  roles: roles,
  activeRole: roles.first,
  staffProfileId: roles.contains(AppRole.trainer) ? 'staff-1' : null,
  profiles: const [],
  activeProfileId: null,
);

class _Auth extends AuthController {
  _Auth(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

class _FakeFeedApi implements CalendarFeedApi {
  bool active = false;
  DateTime? createdAt;
  int created = 0;
  int turnedOff = 0;

  @override
  Future<CalendarFeedStatus> fetchStatus() async =>
      CalendarFeedStatus(active: active, createdAt: active ? createdAt : null);

  @override
  Future<CalendarFeedLink> create() async {
    created++;
    active = true;
    createdAt = DateTime.utc(2026, 11, 26, 15, created);
    return CalendarFeedLink(
      url:
          'https://api.example.com/api/v1/calendar-feeds/token-$created/events.ics',
      webcalUrl:
          'webcal://api.example.com/api/v1/calendar-feeds/token-$created/events.ics',
      createdAt: createdAt!,
    );
  }

  @override
  Future<void> turnOff() async {
    turnedOff++;
    active = false;
  }
}

void main() {
  late _FakeFeedApi api;
  late List<Uri> opened;

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    api = _FakeFeedApi();
    opened = [];
  });

  Future<void> pump(WidgetTester tester, List<AppRole> roles) async {
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        retry: (_, _) => null,
        overrides: [
          authControllerProvider.overrideWith(() => _Auth(_session(roles))),
          calendarFeedApiProvider.overrideWithValue(api),
          externalLauncherProvider.overrideWithValue((uri) async {
            opened.add(uri);
            return true;
          }),
        ],
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('pl'),
          home: CalendarSyncScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('a client turns the sync on and adds the calendar directly', (
    tester,
  ) async {
    await pump(tester, [AppRole.client]);
    expect(find.text('Włącz synchronizację'), findsOneWidget);

    await tester.tap(find.byKey(const Key('calendarSyncEnable')));
    await tester.pumpAndSettle();
    expect(api.created, 1);

    await tester.tap(find.byKey(const Key('calendarSyncApple')));
    await tester.pumpAndSettle();
    expect(find.text('Co dodać do kalendarza?'), findsNothing);
    expect(
      opened.single.toString(),
      'webcal://api.example.com/api/v1/calendar-feeds/token-1/events.ics',
    );

    await tester.tap(find.byKey(const Key('calendarSyncGoogle')));
    await tester.pumpAndSettle();
    expect(opened.last.host, 'calendar.google.com');
    expect(
      opened.last.queryParameters['cid'],
      'https://api.example.com/api/v1/calendar-feeds/token-1/events.ics',
    );
  });

  testWidgets('an account with both roles chooses what to add', (tester) async {
    await pump(tester, [AppRole.client, AppRole.trainer]);
    await tester.tap(find.byKey(const Key('calendarSyncEnable')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('calendarSyncApple')));
    await tester.pumpAndSettle();
    expect(find.text('Co dodać do kalendarza?'), findsOneWidget);
    expect(find.text('Wszystko'), findsOneWidget);
    expect(find.text('Tylko moje zajęcia'), findsOneWidget);
    await tester.tap(find.text('Tylko zajęcia, które prowadzę'));
    await tester.pumpAndSettle();
    expect(opened.single.toString(), endsWith('/events.ics?role=TRAINER'));

    await tester.tap(find.byKey(const Key('calendarSyncGoogle')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('calendarSyncScope.all')));
    await tester.pumpAndSettle();
    expect(opened.last.queryParameters['cid'], endsWith('/events.ics'));
  });

  testWidgets('copies the link', (tester) async {
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await pump(tester, [AppRole.trainer]);
    await tester.tap(find.byKey(const Key('calendarSyncEnable')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('calendarSyncCopy')));
    await tester.pumpAndSettle();

    expect(
      copied,
      'https://api.example.com/api/v1/calendar-feeds/token-1/events.ics',
    );
    expect(find.textContaining('Link skopiowany'), findsOneWidget);
  });

  testWidgets(
    'a link created on another device must be replaced to add it here',
    (tester) async {
      api
        ..active = true
        ..createdAt = DateTime.utc(2026, 11, 1);
      await pump(tester, [AppRole.client]);

      expect(find.textContaining('na innym urządzeniu'), findsOneWidget);
      expect(find.byKey(const Key('calendarSyncApple')), findsNothing);

      await tester.tap(find.byKey(const Key('calendarSyncRegenerate')));
      await tester.pumpAndSettle();
      expect(find.text('Wygenerować nowy link?'), findsOneWidget);
      await tester.tap(find.byKey(const Key('calendarSyncConfirm')));
      await tester.pumpAndSettle();

      expect(api.created, 1);
      expect(find.byKey(const Key('calendarSyncApple')), findsOneWidget);
    },
  );

  testWidgets('turning the sync off asks first and forgets the link', (
    tester,
  ) async {
    await pump(tester, [AppRole.client]);
    await tester.tap(find.byKey(const Key('calendarSyncEnable')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('calendarSyncTurnOff')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Anuluj'));
    await tester.pumpAndSettle();
    expect(api.turnedOff, 0);

    await tester.tap(find.byKey(const Key('calendarSyncTurnOff')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('calendarSyncConfirm')));
    await tester.pumpAndSettle();

    expect(api.turnedOff, 1);
    expect(find.byKey(const Key('calendarSyncEnable')), findsOneWidget);
    expect(
      await const FlutterSecureStorage().read(
        key: 'calendar_feed_link.account-1',
      ),
      isNull,
    );
  });
}
