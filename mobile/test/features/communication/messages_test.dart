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
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/communication/data/open_conversation.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/chat_pickers.dart';
import 'package:mobile/features/communication/presentation/conversation_screen.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/features/communication/presentation/new_group_chat_screen.dart';
import 'package:mobile/features/communication/presentation/widgets/chat_avatar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _trainer = Session(
  accountId: 'trainer-account',
  displayName: 'Marek Nowak',
  roles: [AppRole.trainer],
  activeRole: AppRole.trainer,
  staffProfileId: 'staff-1',
  profiles: [],
  activeProfileId: null,
);

const _client = Session(
  accountId: 'client-account',
  displayName: 'Anna Kowalska',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [Profile(id: 'self', displayName: 'Anna', isChild: false)],
  activeProfileId: 'self',
);

class _FakeConversationsApi extends ConversationsApi {
  _FakeConversationsApi() : super(Dio());

  List<ConversationSummary> summaries = [];
  final conversations = <String, Conversation>{};
  final messages = <String, List<ChatMessage>>{};
  List<MessageableTrainer> trainers = [];
  List<MessageableClient> clients = [];

  /// Messages deleted by someone else, reported to a poll with deletedSince.
  final deletedElsewhere = <String>[];
  final deletedSinceSeen = <String?>[];
  GroupChatTargets targets = const GroupChatTargets(groups: [], events: []);
  final sent = <String>[];
  final created = <Map<String, Object?>>[];
  final started = <String>[];
  final hidden = <String>[];
  int? startStatus;

  @override
  Future<List<ConversationSummary>> fetchConversations() async =>
      List.of(summaries);

  @override
  Future<int> fetchUnreadCount() async => 0;

  @override
  Future<Conversation> fetchConversation(String id) async =>
      conversations[id] ??
      (throw const ApiException(statusCode: 404, message: 'Not found'));

  @override
  Future<MessagePage> fetchMessages(
    String conversationId, {
    String? before,
    String? after,
    String? deletedSince,
  }) async {
    final all = messages[conversationId] ?? const [];
    if (after != null) {
      deletedSinceSeen.add(deletedSince);
      final index = all.indexWhere((m) => m.id == after);
      return MessagePage(
        items: all.sublist(index + 1),
        hasMore: false,
        deletedIds: deletedSince == null ? const [] : List.of(deletedElsewhere),
        nextDeletedSince: 'cursor-${deletedSinceSeen.length}',
      );
    }
    return MessagePage(
      items: before == null ? all : const [],
      hasMore: false,
      nextDeletedSince: 'cursor-0',
    );
  }

  @override
  Future<ChatMessage> send(String conversationId, String body) async {
    sent.add(body);
    final message = _message(
      'sent-${sent.length}',
      body,
      sender: 'me',
      name: 'Ja',
      isMine: true,
    );
    messages.putIfAbsent(conversationId, () => []).add(message);
    return message;
  }

  @override
  Future<void> markRead(String conversationId) async {}

  @override
  Future<void> hide(String conversationId) async => hidden.add(conversationId);

  final repliesChanges = <bool>[];

  @override
  Future<Conversation> setClientsCanReply(
    String conversationId,
    bool value,
  ) async {
    repliesChanges.add(value);
    final current = conversations[conversationId]!;
    final updated = _group(
      current.id,
      current.title,
      isLeader: true,
      clientsCanReply: value,
    );
    conversations[conversationId] = updated;
    return updated;
  }

  @override
  Future<List<MessageableTrainer>> fetchTrainers() async => trainers;

  @override
  Future<List<MessageableClient>> fetchClients() async => clients;

  @override
  Future<Conversation> startWithTrainee(String customerId) async {
    started.add(customerId);
    final conversation = _direct('new-$customerId', 'Klient');
    conversations[conversation.id] = conversation;
    return conversation;
  }

  @override
  Future<Conversation> startWithTrainer(String staffId) async {
    final status = startStatus;
    if (status != null) {
      throw ApiException(statusCode: status, message: 'Error');
    }
    started.add(staffId);
    final conversation = _direct('new-$staffId', 'Trener');
    conversations[conversation.id] = conversation;
    return conversation;
  }

  @override
  Future<GroupChatTargets> fetchGroupChatTargets() async => targets;

  @override
  Future<Conversation> createGroupChat({
    String? trainingGroupId,
    String? eventId,
    required String title,
    required bool clientsCanReply,
  }) async {
    created.add({
      'trainingGroupId': trainingGroupId,
      'eventId': eventId,
      'title': title,
      'clientsCanReply': clientsCanReply,
    });
    final conversation = _group('created', title);
    conversations[conversation.id] = conversation;
    return conversation;
  }
}

class _SignedIn extends AuthController {
  _SignedIn(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

ChatMessage _message(
  String id,
  String body, {
  required String sender,
  required String name,
  bool isMine = false,
  DateTime? at,
}) => ChatMessage(
  id: id,
  senderAccountId: sender,
  senderName: name,
  isMine: isMine,
  body: body,
  isDeleted: false,
  createdAt: at ?? DateTime(2026, 11, 5, 10, 30),
  canDelete: isMine,
);

Conversation _direct(String id, String name) => Conversation(
  id: id,
  kind: ConversationKind.direct,
  title: name,
  counterpart: ChatPerson(accountId: 'other-$id', displayName: name),
  clientsCanReply: true,
  hasLeft: false,
  isClosed: false,
  isLeader: false,
  canWrite: true,
  canHide: false,
  leaders: const [],
  members: const [],
);

Conversation _group(
  String id,
  String title, {
  bool hasLeft = false,
  bool canWrite = true,
  bool isLeader = false,
  bool clientsCanReply = true,
}) => Conversation(
  id: id,
  kind: ConversationKind.group,
  title: title,
  counterpart: null,
  clientsCanReply: clientsCanReply,
  hasLeft: hasLeft,
  isClosed: false,
  isLeader: isLeader,
  canWrite: canWrite,
  canHide: hasLeft,
  leaders: const [ChatPerson(accountId: 'trainer', displayName: 'Ewa Lis')],
  members: const [
    ChatPerson(accountId: 'a', displayName: 'Ola Nowak'),
    ChatPerson(accountId: 'b', displayName: 'Iza Kos'),
  ],
);

ConversationSummary _summary(
  String id,
  String title,
  ConversationKind kind, {
  int unread = 0,
}) => ConversationSummary(
  id: id,
  kind: kind,
  title: title,
  counterpart: kind == ConversationKind.direct
      ? ChatPerson(accountId: 'acc-$id', displayName: title)
      : null,
  eventStartsAt: null,
  hasLeft: false,
  isClosed: false,
  lastMessage: MessagePreview(
    senderName: null,
    isMine: true,
    body: 'Cześć',
    isDeleted: false,
    createdAt: DateTime.now(),
  ),
  unreadCount: unread,
);

void main() {
  late _FakeConversationsApi api;
  var notificationCountLoads = 0;

  setUp(() => api = _FakeConversationsApi());

  Future<void> pump(
    WidgetTester tester, {
    required Session session,
    required String initialLocation,
  }) async {
    final role = session.activeRole.name;
    final router = GoRouter(
      initialLocation: initialLocation,
      routes: [
        GoRoute(
          path: '/$role/messages/chat/:id',
          builder: (_, state) =>
              ConversationScreen(conversationId: state.pathParameters['id']!),
        ),
        GoRoute(
          path: '/$role/messages',
          builder: (_, _) => const MessagesScreen(),
          routes: [
            GoRoute(
              path: 'to-trainer',
              builder: (_, _) => const TrainerPickerScreen(),
            ),
            GoRoute(
              path: 'to-trainee',
              builder: (_, _) => const TraineePickerScreen(),
            ),
            GoRoute(
              path: 'new-chat',
              builder: (_, _) => const NewGroupChatScreen(),
            ),
          ],
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _SignedIn(session)),
          conversationsApiProvider.overrideWithValue(api),
          unreadCountProvider.overrideWith((ref, scope) async {
            notificationCountLoads++;
            return UnreadNotifications.none;
          }),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  group('conversation list', () {
    testWidgets('filters private and group chats', (tester) async {
      api.summaries = [
        _summary('1', 'Marta Zielińska', ConversationKind.direct, unread: 2),
        _summary('2', 'Pole dance wt/czw', ConversationKind.group),
      ];
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages',
      );

      expect(find.text('Marta Zielińska'), findsOneWidget);
      expect(find.text('Pole dance wt/czw'), findsOneWidget);

      await tester.tap(find.widgetWithText(FilterChip, 'Grupowe'));
      await tester.pumpAndSettle();
      expect(find.text('Marta Zielińska'), findsNothing);
      expect(find.text('Pole dance wt/czw'), findsOneWidget);

      await tester.tap(find.widgetWithText(FilterChip, 'Prywatne'));
      await tester.pumpAndSettle();
      expect(find.text('Marta Zielińska'), findsOneWidget);
      expect(find.text('Pole dance wt/czw'), findsNothing);
    });

    testWidgets('the trainer chooses what kind of chat to start', (
      tester,
    ) async {
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages',
      );

      await tester.tap(find.byType(FloatingActionButton));
      await tester.pumpAndSettle();

      expect(find.text('Do klienta'), findsOneWidget);
      expect(find.text('Czat grupowy'), findsOneWidget);
      expect(find.text('Czat zajęć'), findsNothing);

      // One option for both kinds; the form starts on a fixed group.
      await tester.tap(find.text('Czat grupowy'));
      await tester.pumpAndSettle();
      final segments = tester.widget<SegmentedButton<bool>>(
        find.byType(SegmentedButton<bool>),
      );
      expect(segments.selected, {false});
    });

    testWidgets('the trainer writes to a participant of their classes', (
      tester,
    ) async {
      api.clients = const [
        MessageableClient(
          customerId: 'c1',
          displayName: 'Marta Zielińska',
          isChild: false,
          isTrainee: true,
          classes: ['Pole dance wt/czw'],
          conversationId: null,
        ),
        MessageableClient(
          customerId: 'c2',
          displayName: 'Zosia Nowak',
          isChild: true,
          isTrainee: false,
          classes: ['Stretching'],
          conversationId: null,
        ),
      ];
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages',
      );
      await tester.tap(find.byType(FloatingActionButton));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Do klienta'));
      await tester.pumpAndSettle();

      expect(find.text('Podopieczny · Pole dance wt/czw'), findsOneWidget);
      expect(find.text('Dziecko · Stretching'), findsOneWidget);

      await tester.tap(find.text('Zosia Nowak'));
      await tester.pumpAndSettle();

      expect(api.started, ['c2']);
      expect(find.byType(ConversationScreen), findsOneWidget);
      expect(find.text('klient'), findsOneWidget);
    });

    testWidgets('a client picks a trainer and lands in the new chat', (
      tester,
    ) async {
      api.trainers = const [
        MessageableTrainer(
          staffId: 'staff-1',
          displayName: 'Ewa Lis',
          conversationId: null,
        ),
        MessageableTrainer(
          staffId: 'staff-2',
          displayName: 'Jan Wrona',
          conversationId: null,
        ),
      ];
      await pump(tester, session: _client, initialLocation: '/client/messages');

      await tester.tap(find.byType(FloatingActionButton));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'wro');
      await tester.pumpAndSettle();
      expect(find.text('Ewa Lis'), findsNothing);

      await tester.tap(find.text('Jan Wrona'));
      await tester.pumpAndSettle();

      expect(api.started, ['staff-2']);
      expect(find.byType(ConversationScreen), findsOneWidget);
      expect(find.text('trener'), findsOneWidget);
    });

    testWidgets('a trainee without an account cannot be written to', (
      tester,
    ) async {
      api
        ..startStatus = 422
        ..trainers = const [
          MessageableTrainer(
            staffId: 'staff-1',
            displayName: 'Ewa Lis',
            conversationId: null,
          ),
        ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/to-trainer',
      );

      await tester.tap(find.text('Ewa Lis'));
      await tester.pumpAndSettle();

      expect(find.byType(TrainerPickerScreen), findsOneWidget);
      expect(
        find.text('Ten klient nie ma konta, do którego można napisać.'),
        findsOneWidget,
      );
    });
  });

  group('conversation', () {
    testWidgets('a group chat names a sender once, above their run', (
      tester,
    ) async {
      api.conversations['g'] = _group('g', 'Pole dance wt/czw');
      api.messages['g'] = [
        _message('1', 'Pierwsza', sender: 'a', name: 'Ola Nowak'),
        _message('2', 'Druga', sender: 'a', name: 'Ola Nowak'),
        _message('3', 'Trzecia', sender: 'b', name: 'Iza Kos'),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/g',
      );

      expect(find.text('Ola Nowak'), findsOneWidget);
      expect(find.text('Iza Kos'), findsOneWidget);
      final name = tester.widget<Text>(find.text('Ola Nowak'));
      expect(
        name.style?.color,
        Theme.of(
          tester.element(find.text('Ola Nowak')),
        ).colorScheme.onSurfaceVariant,
      );
      expect(
        tester.getBottomLeft(find.text('Ola Nowak')).dy,
        lessThan(tester.getTopLeft(find.text('Pierwsza')).dy),
      );
      // The gap between two senders is bigger than within one sender's run.
      final withinRun =
          tester.getTopLeft(find.text('Druga')).dy -
          tester.getBottomLeft(find.text('Pierwsza')).dy;
      final betweenSenders =
          tester.getTopLeft(find.text('Iza Kos')).dy -
          tester.getBottomLeft(find.text('Druga')).dy;
      expect(betweenSenders, greaterThan(withinRun));
    });

    testWidgets('a chain shows its first time; others show after a tap', (
      tester,
    ) async {
      final now = DateTime.now();
      final yesterday = DateTime(now.year, now.month, now.day - 1);
      DateTime at(DateTime day, int hour, int minute) =>
          DateTime(day.year, day.month, day.day, hour, minute);
      api.conversations['d'] = _direct('d', 'Marta Zielińska');
      api.messages['d'] = [
        _message(
          '1',
          'Dzień dobry',
          sender: 'x',
          name: 'Marta Zielińska',
          at: at(yesterday, 10, 30),
        ),
        _message(
          '2',
          'Jestem już',
          sender: 'me',
          name: 'Ja',
          isMine: true,
          at: at(yesterday, 10, 39),
        ),
        // Ten minutes without a message start a new chain.
        _message(
          '3',
          'Do zobaczenia',
          sender: 'x',
          name: 'Marta Zielińska',
          at: at(yesterday, 10, 49),
        ),
        // So does a new day.
        _message(
          '4',
          'Cześć',
          sender: 'x',
          name: 'Marta Zielińska',
          at: at(now, 0, 0),
        ),
      ];
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages/chat/d',
      );
      // One label with the day and the time; no separate day separator.
      expect(find.text('wczoraj, 10:30'), findsOneWidget);
      expect(find.text('wczoraj, 10:39'), findsNothing);
      expect(find.text('wczoraj, 10:49'), findsOneWidget);
      expect(find.text('00:00'), findsOneWidget);
      expect(find.text('wczoraj'), findsNothing);
      expect(find.text('dzisiaj'), findsNothing);
      expect(
        tester.getCenter(find.text('wczoraj, 10:30')).dx,
        moreOrLessEquals(tester.getSize(find.byType(Scaffold)).width / 2),
      );

      await tester.tap(find.text('Jestem już'));
      await tester.pumpAndSettle();
      expect(find.text('wczoraj, 10:39'), findsOneWidget);

      // The label of a chain's first message cannot be hidden.
      await tester.tap(find.text('Dzień dobry'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Dzień dobry'));
      await tester.pumpAndSettle();
      expect(find.text('wczoraj, 10:30'), findsOneWidget);
      expect(find.text('klient'), findsOneWidget);
    });

    testWidgets('the chain label names the day shortly within a week', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('pl'),
          home: Builder(
            builder: (context) {
              final l10n = AppLocalizations.of(context);
              // Monday, 9 November 2026.
              final now = DateTime(2026, 11, 9, 12);
              return Column(
                children: [
                  for (final time in [
                    DateTime(2026, 11, 9, 14, 5),
                    DateTime(2026, 11, 8, 18, 30),
                    DateTime(2026, 11, 6, 18, 30),
                    DateTime(2026, 11, 5, 9, 0),
                    DateTime(2026, 11, 2, 9, 0),
                    DateTime(2025, 12, 6, 9, 0),
                  ])
                    Text(formatChainTime(time, l10n, now)),
                ],
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('14:05'), findsOneWidget);
      expect(find.text('wczoraj, 18:30'), findsOneWidget);
      expect(find.text('pt, 18:30'), findsOneWidget);
      expect(find.text('czw, 09:00'), findsOneWidget);
      expect(find.text('2 listopada, 09:00'), findsOneWidget);
      expect(find.text('6 grudnia 2025, 09:00'), findsOneWidget);
    });

    testWidgets('a message deleted by the other person disappears while open', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Marta Zielińska');
      api.messages['d'] = [
        _message('1', 'Pomyłka', sender: 'x', name: 'Marta Zielińska'),
        _message('2', 'Dzień dobry', sender: 'x', name: 'Marta Zielińska'),
      ];
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages/chat/d',
      );
      expect(find.text('Pomyłka'), findsOneWidget);

      api.deletedElsewhere.add('1');
      await tester.pump(messagesPollInterval);
      await tester.pumpAndSettle();

      expect(api.deletedSinceSeen.first, 'cursor-0');
      expect(find.text('Pomyłka'), findsNothing);
      expect(find.text('Dzień dobry'), findsOneWidget);
    });

    testWidgets(
      'a push about the open conversation shows its message at once',
      (tester) async {
        api.conversations['d'] = _direct('d', 'Marta Zielińska');
        api.messages['d'] = [
          _message('1', 'Dzień dobry', sender: 'x', name: 'Marta Zielińska'),
        ];
        await pump(
          tester,
          session: _trainer,
          initialLocation: '/trainer/messages/chat/d',
        );
        final open = ProviderScope.containerOf(
          tester.element(find.byType(ConversationScreen)),
        ).read(openConversationProvider);
        expect(open.id, 'd');

        api.messages['d']!.add(
          _message(
            '2',
            'Będę 10 min później',
            sender: 'x',
            name: 'Marta Zielińska',
          ),
        );
        open.messageArrived('other');
        await tester.pumpAndSettle();
        expect(find.text('Będę 10 min później'), findsNothing);
        open.messageArrived('d');
        await tester.pumpAndSettle();
        expect(find.text('Będę 10 min później'), findsOneWidget);

        await tester.pumpWidget(const SizedBox.shrink());
        expect(open.id, isNull);
      },
    );

    testWidgets('reading a conversation refreshes the bell', (tester) async {
      api.summaries = [
        _summary('d', 'Ewa Lis', ConversationKind.direct, unread: 1),
      ];
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api.messages['d'] = [
        _message('1', 'Dzień dobry', sender: 'x', name: 'Ewa Lis'),
      ];
      await pump(tester, session: _client, initialLocation: '/client/messages');
      final listed = notificationCountLoads;

      // Opening it reads the conversation and the notifications about it: back
      // on the list, the bell is up to date.
      await tester.tap(find.text('Ewa Lis'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(notificationCountLoads, greaterThan(listed));
    });

    testWidgets('sends a message', (tester) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      await tester.enterText(find.byType(TextField), '  Do zobaczenia  ');
      await tester.tap(find.byTooltip('Wyślij'));
      await tester.pumpAndSettle();

      expect(api.sent, ['Do zobaczenia']);
      expect(find.text('Do zobaczenia'), findsOneWidget);
    });

    testWidgets('a client who left reads the history and can remove the chat', (
      tester,
    ) async {
      api.conversations['g'] = _group(
        'g',
        'Pole dance wt/czw',
        hasLeft: true,
        canWrite: false,
      );
      api
        ..messages['g'] = [
          _message('1', 'Stara wiadomość', sender: 'a', name: 'Ola Nowak'),
        ]
        ..summaries = [
          _summary('g', 'Pole dance wt/czw', ConversationKind.group),
        ];
      await pump(tester, session: _client, initialLocation: '/client/messages');
      await tester.tap(find.text('Pole dance wt/czw'));
      await tester.pumpAndSettle();

      expect(find.text('Stara wiadomość'), findsOneWidget);
      expect(find.byType(TextField), findsNothing);
      expect(find.text('Nie należysz już do tej grupy'), findsWidgets);

      await tester.tap(find.byType(PopupMenuButton<void>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Usuń czat'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Usuń czat'));
      await tester.pumpAndSettle();

      expect(api.hidden, ['g']);
      expect(find.byType(MessagesScreen), findsOneWidget);
    });

    testWidgets('the trainer stops clients from replying in the chat info', (
      tester,
    ) async {
      api.conversations['g'] = _group('g', 'Pole dance wt/czw', isLeader: true);
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages/chat/g',
      );
      expect(
        find.text('Członkowie: 2 · klienci mogą odpowiadać'),
        findsOneWidget,
      );

      await tester.tap(find.byTooltip('Informacje o czacie'));
      await tester.pumpAndSettle();
      expect(find.text('Prowadzący'), findsOneWidget);
      expect(find.text('Członkowie · 2'), findsOneWidget);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();

      expect(api.repliesChanges, [false]);
      expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();
      expect(
        find.text('Członkowie: 2 · piszą tylko prowadzący'),
        findsOneWidget,
      );
    });

    testWidgets('user avatars are square', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: PersonAvatar(name: 'Ola Nowak', seed: 'a'),
        ),
      );
      expect(find.byType(CircleAvatar), findsNothing);
      expect(find.text('ON'), findsOneWidget);
    });
  });

  group('new group chat', () {
    const groups = [
      GroupChatTarget(
        trainingGroupId: 'g1',
        name: 'Pole dance wt/czw',
        memberCount: 12,
        conversationId: null,
      ),
      GroupChatTarget(
        trainingGroupId: 'g2',
        name: 'Stretching pon',
        memberCount: 5,
        conversationId: 'existing',
      ),
    ];

    testWidgets('creates a chat of a picked group with its name', (
      tester,
    ) async {
      api.targets = const GroupChatTargets(groups: groups, events: []);
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages/new-chat',
      );

      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      expect(find.text('Grupy, które prowadzisz'), findsOneWidget);
      await tester.tap(find.text('Pole dance wt/czw').last);
      await tester.pumpAndSettle();

      expect(
        find.widgetWithText(TextField, 'Pole dance wt/czw'),
        findsOneWidget,
      );
      expect(find.textContaining('Członkowie grupy: 12'), findsOneWidget);

      await tester.tap(find.byType(Switch));
      await tester.tap(find.text('Utwórz czat'));
      await tester.pumpAndSettle();

      expect(api.created, [
        {
          'trainingGroupId': 'g1',
          'eventId': null,
          'title': 'Pole dance wt/czw',
          'clientsCanReply': false,
        },
      ]);
      expect(find.byType(MessagesScreen), findsOneWidget);
    });

    testWidgets('a group that already has a chat opens it instead', (
      tester,
    ) async {
      api.targets = const GroupChatTargets(groups: groups, events: []);
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages/new-chat',
      );

      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Stretching pon').last);
      await tester.pumpAndSettle();

      expect(find.text('Otwórz istniejący czat'), findsOneWidget);
      expect(find.text('Utwórz czat'), findsNothing);
    });

    testWidgets('says when the trainer runs no classes', (tester) async {
      api.targets = const GroupChatTargets(groups: groups, events: []);
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages/new-chat',
      );
      await tester.tap(find.text('Pojedyncze zajęcia'));
      await tester.pumpAndSettle();

      expect(
        find.text('Nie prowadzisz zajęć w najbliższych 30 dniach.'),
        findsOneWidget,
      );
      expect(find.byType(DropdownButtonFormField<String>), findsNothing);
    });
  });
}
