import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/choice_store.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/push/devices_api.dart';
import 'package:mobile/core/push/push_device.dart';
import 'package:mobile/core/push/push_messaging.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

class _Push implements PushMessaging {
  _Push({this.allowed = true, this.currentToken = 'token-1'});

  bool allowed;
  String? currentToken;
  final refreshes = StreamController<String>.broadcast();
  final opened = StreamController<PushMessage>.broadcast();
  final foreground = StreamController<PushMessage>.broadcast();

  @override
  Future<bool> requestPermission() async => allowed;

  @override
  Future<String?> token() async => currentToken;

  @override
  Stream<String> get onTokenRefresh => refreshes.stream;

  @override
  Stream<PushMessage> get onForegroundMessage => foreground.stream;

  @override
  Stream<PushMessage> get onOpened => opened.stream;

  @override
  Future<PushMessage?> initialMessage() async => null;
}

class _Devices implements DevicesApi {
  final registered = <(String, String)>[];
  final unregistered = <String>[];

  @override
  Future<void> register(String token, {required String platform}) async =>
      registered.add((token, platform));

  @override
  Future<void> unregister(String token) async => unregistered.add(token);
}

class _Notifications implements NotificationsApi {
  _Notifications({this.items = const [], this.unread = 0});

  List<AppNotification> items;
  int unread;
  final fetched = <String>[];
  final read = <String>[];
  final readAll = <String>[];

  @override
  Future<NotificationPage> fetch(String role, {int page = 0}) async {
    fetched.add(role);
    return NotificationPage(
      items: items,
      page: page,
      totalItems: items.length,
      totalPages: items.isEmpty ? 0 : 1,
    );
  }

  @override
  Future<int> unreadCount(String role) async => unread;

  @override
  Future<void> markRead(String notificationId) async =>
      read.add(notificationId);

  @override
  Future<void> markAllRead(String role) async {
    readAll.add(role);
    unread = 0;
  }
}

AppNotification _reminder({String id = 'n1', bool unread = true}) =>
    AppNotification(
      id: id,
      type: 'CLASS_REMINDER',
      role: 'CLIENT',
      subjectProfileId: 'child-2',
      title: 'Klient · Maria: Przypomnienie o zajęciach',
      body: 'Pole dance: jutro, godz. 18:00. Sala: Duża.',
      data: const {'eventId': 'event-1', 'profileId': 'child-2'},
      createdAt: DateTime.now(),
      readAt: unread ? null : DateTime.now(),
    );

void main() {
  group('push device', () {
    ProviderContainer container(_Push push, _Devices devices) {
      final container = ProviderContainer(
        overrides: [
          pushMessagingProvider.overrideWithValue(push),
          devicesApiProvider.overrideWithValue(devices),
        ],
      );
      addTearDown(container.dispose);
      return container;
    }

    test('registers its token, follows refreshes and unregisters', () async {
      final push = _Push();
      final devices = _Devices();
      final device = container(push, devices).read(pushDeviceProvider);

      await device.register();
      expect(devices.registered, [('token-1', 'ANDROID')]);

      push.refreshes.add('token-2');
      await pumpEventQueue();
      expect(devices.registered.last, ('token-2', 'ANDROID'));

      await device.unregister();
      await device.unregister();
      expect(devices.unregistered, ['token-2']);

      // Signed out: a refreshed token no longer belongs to anyone.
      push.refreshes.add('token-3');
      await pumpEventQueue();
      expect(devices.registered, hasLength(2));
    });

    test('a refused permission or a missing token registers nothing', () async {
      final devices = _Devices();
      await container(
        _Push(allowed: false),
        devices,
      ).read(pushDeviceProvider).register();
      await container(
        _Push(currentToken: null),
        devices,
      ).read(pushDeviceProvider).register();
      expect(devices.registered, isEmpty);
    });
  });

  group('in the app', () {
    Future<ProviderContainer> pumpApp(
      WidgetTester tester, {
      required _Push push,
      required _Devices devices,
      required _Notifications notifications,
      AuthRepository? auth,
    }) async {
      final container = ProviderContainer(
        overrides: [
          authRepositoryProvider.overrideWithValue(
            auth ?? FakeAuthRepository(),
          ),
          choiceStoreProvider.overrideWithValue(InMemoryChoiceStore()),
          groupClassesProvider.overrideWith((ref, query) async => const []),
          scheduleServiceTypesProvider.overrideWith((ref) async => const {}),
          studioTrainersProvider.overrideWith((ref) async => const []),
          pushMessagingProvider.overrideWithValue(push),
          devicesApiProvider.overrideWithValue(devices),
          notificationsApiProvider.overrideWithValue(notifications),
          unreadMessagesProvider.overrideWith(
            (ref, accountId) => Stream.value(0),
          ),
          conversationsProvider.overrideWith(
            (ref, accountId) async => const [],
          ),
          appConfigProvider.overrideWithValue(
            const AppConfig(
              flavor: AppFlavor.dev,
              apiBaseUrl: 'http://localhost',
            ),
          ),
        ],
      );
      addTearDown(() async {
        await tester.pumpWidget(const SizedBox.shrink());
        container.dispose();
      });
      await tester.pumpWidget(
        UncontrolledProviderScope(container: container, child: const App()),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Zaloguj się'));
      await tester.pumpAndSettle();
      return container;
    }

    String location(ProviderContainer container) =>
        container.read(appRouterProvider).state.matchedLocation;

    testWidgets('signing in registers the device, signing out removes it', (
      tester,
    ) async {
      final devices = _Devices();
      final container = await pumpApp(
        tester,
        push: _Push(),
        devices: devices,
        notifications: _Notifications(),
      );
      expect(devices.registered, [('token-1', 'ANDROID')]);

      await container.read(authControllerProvider.notifier).logout();
      await tester.pumpAndSettle();
      expect(devices.unregistered, ['token-1']);
    });

    testWidgets(
      'a tapped reminder about a child opens the child\'s reservations',
      (tester) async {
        final push = _Push();
        final notifications = _Notifications();
        final container = await pumpApp(
          tester,
          push: push,
          devices: _Devices(),
          notifications: notifications,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();

        push.opened.add(
          const PushMessage(
            data: {
              'type': 'CLASS_REMINDER',
              'notificationId': 'n1',
              'profileId': 'child-2',
            },
          ),
        );
        await tester.pumpAndSettle();

        final session =
            (container.read(authControllerProvider) as Authenticated).session;
        expect(session.activeProfileId, 'child-2');
        expect(location(container), clientReservationsPath);
        expect(notifications.read, ['n1']);
      },
    );

    testWidgets('the inbox lists the active role\'s notifications', (
      tester,
    ) async {
      final notifications = _Notifications(items: [_reminder()], unread: 1);
      final container = await pumpApp(
        tester,
        push: _Push(),
        devices: _Devices(),
        notifications: notifications,
      );
      await tester.tap(find.text('Anna').first);
      await tester.pumpAndSettle();
      // The unread badge on the messages tab.
      expect(
        find.descendant(of: find.byType(Badge), matching: find.text('1')),
        findsOneWidget,
      );

      await tester.tap(find.text('Wiadomości'));
      await tester.pumpAndSettle();
      // The messages screen does not list notifications; its bell opens them.
      expect(
        find.text('Klient · Maria: Przypomnienie o zajęciach'),
        findsNothing,
      );
      await tester.tap(find.byTooltip('Powiadomienia'));
      await tester.pumpAndSettle();
      expect(location(container), clientNotificationsPath);
      expect(notifications.fetched.last, 'CLIENT');
      expect(
        find.text('Klient · Maria: Przypomnienie o zajęciach'),
        findsOneWidget,
      );

      await tester.tap(find.byTooltip('Oznacz wszystkie jako przeczytane'));
      await tester.pumpAndSettle();
      expect(notifications.readAll, ['CLIENT']);
      expect(
        find.descendant(of: find.byType(Badge), matching: find.text('1')),
        findsNothing,
      );

      // Back on the messages screen the bell has no number any more.
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(location(container), clientMessagesPath);
      expect(
        find.descendant(of: find.byType(Badge), matching: find.text('1')),
        findsNothing,
      );
    });

    testWidgets('a message while the app is open shows and refreshes', (
      tester,
    ) async {
      final push = _Push();
      final notifications = _Notifications();
      await pumpApp(
        tester,
        push: push,
        devices: _Devices(),
        notifications: notifications,
      );
      await tester.tap(find.text('Anna').first);
      await tester.pumpAndSettle();

      notifications.unread = 1;
      push.foreground.add(
        const PushMessage(
          title: 'Przypomnienie o zajęciach',
          body: 'Pole dance: dziś, godz. 18:00.',
          data: {'type': 'CLASS_REMINDER'},
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.text('Przypomnienie o zajęciach\nPole dance: dziś, godz. 18:00.'),
        findsOneWidget,
      );
      expect(find.text('Pokaż'), findsOneWidget);
      expect(
        find.descendant(of: find.byType(Badge), matching: find.text('1')),
        findsOneWidget,
      );
    });
  });
}
