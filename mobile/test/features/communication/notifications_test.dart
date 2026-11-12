import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/app/app.dart';
import 'package:mobile/app/config/app_config.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/auth/choice_store.dart';
import 'package:mobile/core/auth/fake_auth_repository.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/push/devices_api.dart';
import 'package:mobile/core/push/push_device.dart';
import 'package:mobile/core/push/push_messaging.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/communication/data/open_conversation.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import '../trainings/group_class_fixtures.dart';

class _Push implements PushMessaging {
  _Push({this.allowed = true, this.currentToken = 'token-1'});

  bool allowed;
  String? currentToken;
  final refreshes = StreamController<String>.broadcast();
  final opened = StreamController<PushMessage>.broadcast();
  final foreground = StreamController<PushMessage>.broadcast();
  PushMessage? initial;

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
  Future<PushMessage?> initialMessage() async => initial;
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
  int unreadAboutMessages = 0;
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
  Future<UnreadNotifications> unreadCount(String role) async =>
      UnreadNotifications(count: unread, newMessages: unreadAboutMessages);

  @override
  Future<void> markRead(String notificationId) async {
    read.add(notificationId);
    items = [
      for (final item in items)
        if (item.id == notificationId && item.isUnread)
          AppNotification(
            id: item.id,
            type: item.type,
            title: item.title,
            body: item.body,
            data: item.data,
            createdAt: item.createdAt,
            role: item.role,
            subjectProfileId: item.subjectProfileId,
            readAt: DateTime.now(),
          )
        else
          item,
    ];
    unread = items.where((item) => item.isUnread).length;
    unreadAboutMessages = items
        .where((item) => item.isUnread && item.type == 'NEW_MESSAGE')
        .length;
  }

  @override
  Future<void> markAllRead(String role) async {
    readAll.add(role);
    unread = 0;
  }
}

/// No conversation loads: the tests only follow where a message opens.
class _Conversations extends ConversationsApi {
  _Conversations() : super(Dio());

  @override
  Future<List<ConversationSummary>> fetchConversations() async => const [];

  @override
  Future<int> fetchUnreadCount() async => 0;

  @override
  Future<Conversation> fetchConversation(String id) async =>
      throw const ApiException(statusCode: 404, message: 'Not found');

  @override
  Future<MessagePage> fetchMessages(
    String conversationId, {
    String? before,
    String? after,
    String? deletedSince,
  }) async => const MessagePage(items: [], hasMore: false);

  @override
  Future<void> markRead(String conversationId) async {}
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
      List<ClassQuery>? classReads,
      bool granted = false,
      int unreadMessages = 0,
    }) async {
      final container = ProviderContainer(
        overrides: [
          authRepositoryProvider.overrideWithValue(
            auth ?? FakeAuthRepository(),
          ),
          choiceStoreProvider.overrideWithValue(InMemoryChoiceStore()),
          groupClassesProvider.overrideWith((ref, query) async => const []),
          classReservationsProvider.overrideWith(
            (ref, query) async => const [],
          ),
          groupClassProvider.overrideWith((ref, query) async {
            classReads?.add(query);
            return GroupClass.fromJson(
              granted
                  ? groupClassJson(
                      reserved: true,
                      profileId: query.scope.profileId,
                    )
                  : offeredClassJson(),
            );
          }),
          classParticipantsProvider.overrideWith(
            (ref, query) async => const [],
          ),
          scheduleServiceTypesProvider.overrideWith((ref) async => const {}),
          studioTrainersProvider.overrideWith((ref) async => const []),
          ownPassesProvider.overrideWith((ref, profileId) async => const []),
          pushMessagingProvider.overrideWithValue(push),
          devicesApiProvider.overrideWithValue(devices),
          notificationsApiProvider.overrideWithValue(notifications),
          conversationsApiProvider.overrideWithValue(_Conversations()),
          unreadMessagesProvider.overrideWith(
            (ref, accountId) => Stream.value(unreadMessages),
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

    for (final type in ['WAITLIST_PLACE_OFFERED', 'WAITLIST_PLACE_GRANTED']) {
      testWidgets('$type opens the exact class in the child profile', (
        tester,
      ) async {
        final push = _Push();
        final reads = <ClassQuery>[];
        final notifications = _Notifications();
        final container = await pumpApp(
          tester,
          push: push,
          devices: _Devices(),
          notifications: notifications,
          classReads: reads,
          granted: type == 'WAITLIST_PLACE_GRANTED',
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        // Start in the trainer role to exercise both role and participant switching.
        container
            .read(authControllerProvider.notifier)
            .choose(const SessionChoice(role: AppRole.trainer));
        await tester.pumpAndSettle();
        push.opened.add(
          PushMessage(
            data: {
              'type': type,
              'notificationId': 'waitlist-note',
              'profileId': 'child-2',
              'eventId': 'class-1',
            },
          ),
        );
        await tester.pumpAndSettle();
        final session =
            (container.read(authControllerProvider) as Authenticated).session;
        expect(session.activeRole, AppRole.client);
        expect(session.activeProfileId, 'child-2');
        expect(location(container), clientClassDetailsLocation('class-1'));
        expect(reads.last.scope.profileId, 'child-2');
        expect(notifications.read, ['waitlist-note']);
        expect(
          find.text(
            type == 'WAITLIST_PLACE_OFFERED'
                ? 'Potwierdź miejsce'
                : 'Masz rezerwację',
          ),
          findsOneWidget,
        );
      });
    }

    testWidgets(
      'a waitlist notification tapped before login opens after choosing the account',
      (tester) async {
        final push = _Push()
          ..initial = const PushMessage(
            data: {
              'type': 'WAITLIST_PLACE_OFFERED',
              'profileId': 'child-2',
              'eventId': 'class-1',
            },
          );
        final reads = <ClassQuery>[];
        final container = await pumpApp(
          tester,
          push: push,
          devices: _Devices(),
          notifications: _Notifications(),
          classReads: reads,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        expect(location(container), clientClassDetailsLocation('class-1'));
        expect(reads.last.scope.profileId, 'child-2');
      },
    );

    testWidgets(
      'an inaccessible participant never opens another persons class',
      (tester) async {
        final push = _Push();
        final reads = <ClassQuery>[];
        final container = await pumpApp(
          tester,
          push: push,
          devices: _Devices(),
          notifications: _Notifications(),
          classReads: reads,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        push.opened.add(
          const PushMessage(
            data: {
              'type': 'WAITLIST_PLACE_OFFERED',
              'profileId': 'removed-child',
              'eventId': 'class-1',
            },
          ),
        );
        await tester.pumpAndSettle();
        expect(location(container), clientNotificationsPath);
        expect(reads, isEmpty);
        expect(
          (container.read(authControllerProvider) as Authenticated)
              .session
              .activeProfileId,
          'self',
        );
      },
    );

    testWidgets(
      'tapping the in-app offer opens the same class and marks it read',
      (tester) async {
        final notifications = _Notifications(
          items: [
            AppNotification(
              id: 'offer',
              type: 'WAITLIST_PLACE_OFFERED',
              title: 'Maria: Zwolniło się miejsce',
              body: 'Pole dance. Potwierdź miejsce do 13.11, 15:30.',
              data: const {'profileId': 'child-2', 'eventId': 'class-1'},
              createdAt: DateTime.now(),
            ),
          ],
        );
        final reads = <ClassQuery>[];
        final container = await pumpApp(
          tester,
          push: _Push(),
          devices: _Devices(),
          notifications: notifications,
          classReads: reads,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Wiadomości'));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Powiadomienia'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Maria: Zwolniło się miejsce'));
        await tester.pumpAndSettle();
        expect(location(container), clientClassDetailsLocation('class-1'));
        expect(reads.last.scope.profileId, 'child-2');
        expect(notifications.read, ['offer']);
      },
    );

    testWidgets(
      'a tapped pass notification about a child opens the child\'s passes',
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
              'type': 'PASS_LAST_ENTRY_USED',
              'notificationId': 'n2',
              'passId': 'pass-1',
              'profileId': 'child-2',
            },
          ),
        );
        await tester.pumpAndSettle();

        final session =
            (container.read(authControllerProvider) as Authenticated).session;
        expect(session.activeProfileId, 'child-2');
        expect(location(container), clientPassesPath);
        expect(notifications.read, ['n2']);
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

    testWidgets(
      'an offer separates its key details and keeps the full text behind info',
      (tester) async {
        const body =
            'Pole dance: zajęcia w sali Duża. Potwierdź miejsce przed '
            'upływem terminu. Dodatkowa informacja od studia.';
        final starts = DateTime.now().add(const Duration(days: 2));
        final deadline = DateTime.now().add(const Duration(hours: 2));
        final notifications = _Notifications(
          items: [
            AppNotification(
              id: 'structured-offer',
              type: 'WAITLIST_PLACE_OFFERED',
              title: 'Maria: Zwolniło się miejsce',
              body: body,
              data: {
                'profileId': 'child-2',
                'eventId': 'class-1',
                'eventName': 'Pole dance',
                'eventStartsAt': starts.toIso8601String(),
                'offerExpiresAt': deadline.toIso8601String(),
              },
              createdAt: DateTime.now(),
            ),
          ],
        );
        final reads = <ClassQuery>[];
        final container = await pumpApp(
          tester,
          push: _Push(),
          devices: _Devices(),
          notifications: notifications,
          classReads: reads,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Wiadomości'));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Powiadomienia'));
        await tester.pumpAndSettle();

        expect(find.text('Pole dance'), findsOneWidget);
        expect(find.textContaining('Potwierdź do'), findsOneWidget);
        expect(find.text(body), findsNothing);
        await tester.tap(find.byTooltip('Pełna treść'));
        await tester.pumpAndSettle();
        expect(find.text(body), findsOneWidget);
        expect(location(container), clientNotificationsPath);
        expect(notifications.read, ['structured-offer']);
        expect(reads, isEmpty);

        await tester.tap(find.text('Przejdź do zajęć'));
        await tester.pumpAndSettle();
        expect(location(container), clientClassDetailsLocation('class-1'));
        expect(reads.last.scope.profileId, 'child-2');
      },
    );

    testWidgets(
      'long notifications remain usable on a narrow screen with enlarged text',
      (tester) async {
        final notifications = _Notifications(
          items: [
            AppNotification(
              id: 'long-note',
              type: 'WAITLIST_PLACE_GRANTED',
              title: 'Maria: Masz już zarezerwowane miejsce na zajęciach',
              body:
                  'Pełna informacja o rezerwacji i zasadach uczestnictwa. ' *
                  12,
              data: {
                'profileId': 'child-2',
                'eventId': 'class-1',
                'eventName':
                    'Zajęcia o bardzo długiej nazwie, poziom zaawansowany',
                'eventStartsAt': DateTime.now()
                    .add(const Duration(days: 2))
                    .toIso8601String(),
              },
              createdAt: DateTime.now(),
            ),
          ],
        );
        await pumpApp(
          tester,
          push: _Push(),
          devices: _Devices(),
          notifications: notifications,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Wiadomości'));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Powiadomienia'));
        await tester.pumpAndSettle();

        await tester.binding.setSurfaceSize(const Size(320, 740));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        tester.platformDispatcher.textScaleFactorTestValue = 1.5;
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);

        await tester.tap(find.byTooltip('Pełna treść'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.scrollUntilVisible(
          find.text('Przejdź do zajęć'),
          200,
          scrollable: find.byType(Scrollable).last,
        );
        expect(find.text('Przejdź do zajęć').hitTestable(), findsOneWidget);
      },
    );

    testWidgets(
      'one inbox keeps announcements and pass messages after reading',
      (tester) async {
        final notifications = _Notifications(
          unread: 3,
          items: [
            for (final entry in [
              ('SEED_ANNOUNCEMENT', 'Promocja -20% na karnety'),
              ('PASS_EXPIRING', 'Karnet wkrótce wygaśnie'),
              ('PASS_LAST_ENTRY_USED', 'Wykorzystano ostatnie wejście'),
            ])
              AppNotification(
                id: entry.$1,
                type: entry.$1,
                title: entry.$2,
                body: 'Dodatkowa treść: ${entry.$2}',
                data: const {'profileId': 'child-2'},
                createdAt: DateTime.now(),
              ),
          ],
        );
        final container = await pumpApp(
          tester,
          push: _Push(allowed: false),
          devices: _Devices(),
          notifications: notifications,
        );
        await tester.tap(find.text('Anna').first);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Wiadomości'));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Powiadomienia'));
        await tester.pumpAndSettle();
        expect(find.text('Promocja -20% na karnety'), findsOneWidget);
        expect(find.text('Karnet wkrótce wygaśnie'), findsOneWidget);
        expect(find.text('Wykorzystano ostatnie wejście'), findsOneWidget);
        await tester.tap(find.byTooltip('Jak działają powiadomienia'));
        await tester.pumpAndSettle();
        expect(
          find.textContaining('Push na telefonie dostarcza tę samą wiadomość.'),
          findsOneWidget,
        );
        expect(notifications.read, isEmpty);
        await tester.tap(find.text('Zamknij'));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Pełna treść').first);
        await tester.pumpAndSettle();
        expect(
          find.text('Dodatkowa treść: Promocja -20% na karnety'),
          findsWidgets,
        );
        expect(notifications.read, ['SEED_ANNOUNCEMENT']);
        expect(notifications.unread, 2);
        expect(location(container), clientNotificationsPath);
        await tester.tap(find.text('Zamknij'));
        await tester.pumpAndSettle();
        expect(find.text('Promocja -20% na karnety'), findsOneWidget);
        await tester.tap(find.text('Karnet wkrótce wygaśnie'));
        await tester.pumpAndSettle();
        expect(location(container), clientPassesPath);
        expect(
          (container.read(authControllerProvider) as Authenticated)
              .session
              .activeProfileId,
          'child-2',
        );
        expect(notifications.read.last, 'PASS_EXPIRING');
      },
    );

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
    testWidgets('a tapped message opens its conversation in the role it '
        'concerns', (tester) async {
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
            'type': 'NEW_MESSAGE',
            'notificationId': 'n2',
            'conversationId': 'c1',
            'role': 'TRAINER',
          },
        ),
      );
      await tester.pumpAndSettle();

      final session =
          (container.read(authControllerProvider) as Authenticated).session;
      expect(session.activeRole, AppRole.trainer);
      expect(location(container), '/trainer/messages/chat/c1');
      expect(notifications.read, ['n2']);

      // Back from the conversation lands on the messages tab.
      container.read(appRouterProvider).pop();
      await tester.pumpAndSettle();
      expect(location(container), trainerMessagesPath);
    });

    testWidgets('a message in the conversation on screen refreshes it without '
        'a banner', (tester) async {
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
      final open = container.read(openConversationProvider)..id = 'c1';
      final arrived = <String>[];
      final subscription = open.arrived.listen(arrived.add);
      addTearDown(subscription.cancel);

      push.foreground.add(
        const PushMessage(
          title: 'Anna Wiśniewska',
          body: 'Weźcie wodę i ręcznik.',
          data: {
            'type': 'NEW_MESSAGE',
            'notificationId': 'n3',
            'conversationId': 'c1',
            'role': 'CLIENT',
          },
        ),
      );
      await tester.pumpAndSettle();
      expect(arrived, ['c1']);
      expect(find.text('Pokaż'), findsNothing);
      expect(notifications.read, ['n3']);

      // A message in another conversation is shown as usual.
      push.foreground.add(
        const PushMessage(
          title: 'Pole dance · Anna Wiśniewska',
          body: 'Zmiana sali na 2.',
          data: {
            'type': 'NEW_MESSAGE',
            'notificationId': 'n4',
            'conversationId': 'c2',
            'role': 'CLIENT',
          },
        ),
      );
      await tester.pumpAndSettle();
      expect(arrived, ['c1']);
      expect(
        find.text('Pole dance · Anna Wiśniewska\nZmiana sali na 2.'),
        findsOneWidget,
      );
      await tester.tap(find.text('Pokaż'));
      await tester.pumpAndSettle();
      expect(location(container), '/client/messages/chat/c2');
      expect(notifications.read, ['n3', 'n4']);
    });

    testWidgets('the messages tab counts a message once, the bell its '
        'notification', (tester) async {
      // Three unread messages; two notifications: one about them, one other.
      final notifications = _Notifications(unread: 2)..unreadAboutMessages = 1;
      await pumpApp(
        tester,
        push: _Push(),
        devices: _Devices(),
        notifications: notifications,
        unreadMessages: 3,
      );
      await tester.tap(find.text('Anna').first);
      await tester.pumpAndSettle();
      expect(
        find.descendant(of: find.byType(Badge), matching: find.text('4')),
        findsOneWidget,
      );

      await tester.tap(find.text('Wiadomości'));
      await tester.pumpAndSettle();
      expect(
        find.descendant(of: find.byType(Badge), matching: find.text('2')),
        findsOneWidget,
      );
    });
  });
}
