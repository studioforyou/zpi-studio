import 'dart:io';
import 'dart:math' as math;

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
import 'package:mobile/features/communication/data/attachment_files.dart';
import 'package:mobile/features/communication/data/attachment_picker.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/communication/data/open_conversation.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/chat_pickers.dart';
import 'package:mobile/features/communication/presentation/conversation_media_screen.dart';
import 'package:mobile/features/communication/presentation/conversation_screen.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/features/communication/presentation/new_group_chat_screen.dart';
import 'package:mobile/features/communication/presentation/widgets/chat_avatar.dart';
import 'package:mobile/features/communication/presentation/widgets/message_attachments.dart';
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

  /// How many of the latest messages a conversation opens with; all when
  /// `null`.
  int? latestPageSize;

  /// The messages older ones were asked for before.
  final olderRequests = <String>[];
  bool olderFail = false;

  /// How many photos or files a page has, whatever the app asks for.
  int? attachmentPageSize;
  final attachmentRequests = <String>[];

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
    if (before != null) {
      olderRequests.add(before);
      if (olderFail) {
        throw const ApiException(statusCode: 500, message: 'Error');
      }
      return MessagePage(
        items: all.sublist(0, all.indexWhere((m) => m.id == before)),
        hasMore: false,
      );
    }
    final size = latestPageSize ?? all.length;
    return MessagePage(
      items: all.sublist(math.max(0, all.length - size)),
      hasMore: all.length > size,
      nextDeletedSince: 'cursor-0',
    );
  }

  @override
  Future<ConversationAttachmentPage> fetchAttachments(
    String conversationId,
    AttachmentKind kind, {
    String? before,
    String? after,
    int? limit,
  }) async {
    attachmentRequests.add('${kind.name} before=$before');
    final all = [
      for (final message in messages[conversationId] ?? const <ChatMessage>[])
        if (!message.isDeleted)
          for (final item in ConversationAttachment.ofMessage(message))
            if (item.attachment.isImage == (kind == AttachmentKind.photo)) item,
    ];
    final end = before == null
        ? all.length
        : all.indexWhere((item) => item.attachment.id == before);
    final start = math.max(0, end - (attachmentPageSize ?? limit ?? 50));
    return ConversationAttachmentPage(
      items: all.sublist(start, end),
      hasMore: start > 0,
    );
  }

  final sentAttachments = <List<OutgoingAttachment>>[];

  @override
  Future<ChatMessage> send(
    String conversationId,
    String body, {
    List<OutgoingAttachment> attachments = const [],
    void Function(double progress)? onProgress,
  }) async {
    sent.add(body);
    sentAttachments.add(attachments);
    final message = _message(
      'sent-${sent.length}',
      body.isEmpty ? null : body,
      sender: 'me',
      name: 'Ja',
      isMine: true,
      attachments: [
        for (final attachment in attachments)
          ChatAttachment(
            id: 'file-${attachment.name}',
            fileName: attachment.name,
            contentType: attachment.isImage ? 'image/jpeg' : 'application/pdf',
            sizeBytes: attachment.sizeBytes,
            isImage: attachment.isImage,
          ),
      ],
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

class _FakeAttachmentFiles extends AttachmentFiles {
  _FakeAttachmentFiles(super.api);

  final opened = <String>[];
  final saved = <String>[];
  final inGallery = <String>[];
  OpenOutcome outcome = OpenOutcome.opened;
  GallerySave gallery = GallerySave.saved;

  @override
  Future<File> file(
    String conversationId,
    String messageId,
    ChatAttachment attachment, {
    void Function(double progress)? onProgress,
  }) async => File('/cache/${attachment.id}/${attachment.fileName}');

  @override
  Future<File> preview(
    String conversationId,
    String messageId,
    ChatAttachment photo,
  ) async => photo.hasThumbnail
      ? File('/cache/thumbnails/${photo.id}.jpg')
      : file(conversationId, messageId, photo);

  @override
  Future<File?> cachedPreview(ChatAttachment photo) async => null;

  @override
  Future<OpenOutcome> open(File file, String contentType) async {
    opened.add(file.path);
    return outcome;
  }

  @override
  Future<bool> save(File file, ChatAttachment attachment) async {
    saved.add(attachment.fileName);
    return true;
  }

  @override
  Future<GallerySave> saveToGallery(File file) async {
    if (gallery == GallerySave.saved) inGallery.add(file.path);
    return gallery;
  }
}

class _FakeAttachmentPicker extends AttachmentPicker {
  List<OutgoingAttachment> documents = [];

  @override
  Future<List<OutgoingAttachment>> pickDocuments() async => documents;
}

class _SignedIn extends AuthController {
  _SignedIn(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

ChatMessage _message(
  String id,
  String? body, {
  required String sender,
  required String name,
  bool isMine = false,
  DateTime? at,
  List<ChatAttachment> attachments = const [],
}) => ChatMessage(
  id: id,
  senderAccountId: sender,
  senderName: name,
  isMine: isMine,
  body: body,
  isDeleted: false,
  createdAt: at ?? DateTime(2026, 11, 5, 10, 30),
  canDelete: isMine,
  attachments: attachments,
);

const _photo = ChatAttachment(
  id: 'photo-1',
  fileName: 'IMG_0042.jpg',
  contentType: 'image/jpeg',
  sizeBytes: 640 * 1024,
  isImage: true,
  width: 1920,
  height: 2560,
);

const _plan = ChatAttachment(
  id: 'pdf-1',
  fileName: 'Plan treningowy.pdf',
  contentType: 'application/pdf',
  sizeBytes: 1468006,
  isImage: false,
);

ChatAttachment _photoNamed(String name) => ChatAttachment(
  id: 'photo-$name',
  fileName: '$name.jpg',
  contentType: 'image/jpeg',
  sizeBytes: 1024,
  isImage: true,
);

/// The file of the photo shown in the photo viewer.
String _shownPhoto(WidgetTester tester) {
  final image = tester.widget<Image>(
    find.descendant(
      of: find.byType(PhotoViewerScreen),
      matching: find.byType(Image),
    ),
  );
  return (image.image as FileImage).file.path;
}

Finder _thumbnailOf(String photoId) => find.byWidgetPredicate(
  (widget) => widget is PhotoThumbnail && widget.photo.id == photoId,
);

OutgoingAttachment _picked(String name, int sizeBytes) => OutgoingAttachment(
  path: '/picked/$name',
  name: name,
  sizeBytes: sizeBytes,
  isImage: false,
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
  MessagePreview? last,
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
  lastMessage:
      last ??
      MessagePreview(
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
  late _FakeAttachmentFiles files;
  late _FakeAttachmentPicker picker;
  var notificationCountLoads = 0;

  setUp(() {
    api = _FakeConversationsApi();
    files = _FakeAttachmentFiles(api);
    picker = _FakeAttachmentPicker();
  });

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
          attachmentFilesProvider.overrideWithValue(files),
          attachmentPickerProvider.overrideWithValue(picker),
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
    testWidgets('a message without text says what is attached', (tester) async {
      api.summaries = [
        _summary(
          '1',
          'Marta Zielińska',
          ConversationKind.direct,
          last: MessagePreview(
            senderName: 'Marta Zielińska',
            isMine: false,
            body: null,
            isDeleted: false,
            createdAt: DateTime.now(),
            attachmentCount: 3,
            imageCount: 3,
          ),
        ),
        _summary(
          '2',
          'Pole dance wt/czw',
          ConversationKind.group,
          last: MessagePreview(
            senderName: null,
            isMine: true,
            body: null,
            isDeleted: false,
            createdAt: DateTime.now(),
            attachmentCount: 2,
            imageCount: 1,
          ),
        ),
      ];
      await pump(
        tester,
        session: _trainer,
        initialLocation: '/trainer/messages',
      );

      expect(find.text('3 zdjęcia'), findsOneWidget);
      expect(find.text('Ty: 2 załączniki'), findsOneWidget);
    });

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

    testWidgets('sends a document picked from the phone without text', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      picker.documents = [_picked('wyniki.pdf', 120 * 1024)];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      await tester.tap(find.byTooltip('Dodaj załącznik'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Plik'));
      await tester.pumpAndSettle();
      // Waiting above the text field until it is sent, and can be taken out.
      expect(find.byTooltip('Usuń załącznik'), findsOneWidget);
      await tester.tap(find.byTooltip('Wyślij'));
      await tester.pumpAndSettle();

      expect(api.sent, ['']);
      expect(api.sentAttachments.single.map((file) => file.name), [
        'wyniki.pdf',
      ]);
      expect(find.byTooltip('Usuń załącznik'), findsNothing);
      expect(find.text('wyniki.pdf'), findsOneWidget);
      expect(find.text('PDF · 120 KB'), findsOneWidget);
    });

    testWidgets('leaves out files the backend would not accept', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      picker.documents = [
        _picked('skan.pdf', 11 * 1024 * 1024),
        _picked('plan.docx', 200 * 1024),
        // Word and Excel files from before 2007 too.
        _picked('stary plan.doc', 90 * 1024),
        _picked('tabela.xls', 40 * 1024),
        _picked('lista.csv', 1024),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      await tester.tap(find.byTooltip('Dodaj załącznik'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Plik'));
      await tester.pumpAndSettle();

      expect(find.byTooltip('Usuń załącznik'), findsNWidgets(3));
      expect(find.text('plan.docx'), findsOneWidget);
      expect(find.text('stary plan.doc'), findsOneWidget);
      expect(find.text('tabela.xls'), findsOneWidget);
      expect(
        find.textContaining('Tego pliku nie można wysłać'),
        findsOneWidget,
      );

      await tester.tap(find.byTooltip('Usuń załącznik').first);
      await tester.pumpAndSettle();
      expect(find.byTooltip('Usuń załącznik'), findsNWidgets(2));
    });

    testWidgets('a HEIC photo is sent from the gallery, not as a file', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      picker.documents = [_picked('IMG_0001.HEIC', 2 * 1024 * 1024)];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      await tester.tap(find.byTooltip('Dodaj załącznik'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Plik'));
      await tester.pumpAndSettle();

      expect(find.byTooltip('Usuń załącznik'), findsNothing);
      expect(
        find.text(
          'Zdjęcie HEIC wyślij przez „Zdjęcia z galerii”: zostanie zapisane '
          'jako JPG.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('shows photos and opens documents in another app', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api.messages['d'] = [
        _message(
          '1',
          'Plan na ten tydzień',
          sender: 'x',
          name: 'Ewa Lis',
          attachments: const [_photo, _plan],
        ),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      expect(find.byType(PhotoThumbnail), findsOneWidget);
      expect(find.text('Plan treningowy.pdf'), findsOneWidget);
      expect(find.text('PDF · 1,4 MB'), findsOneWidget);
      expect(find.text('Plan na ten tydzień'), findsOneWidget);

      await tester.tap(find.text('Plan treningowy.pdf'));
      await tester.pumpAndSettle();
      expect(files.opened, ['/cache/pdf-1/Plan treningowy.pdf']);

      // Without an app for the file, it can be saved on the phone instead.
      files.outcome = OpenOutcome.noApp;
      await tester.tap(find.text('Plan treningowy.pdf'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Brak aplikacji'), findsOneWidget);
      await tester.tap(
        find.widgetWithText(SnackBarAction, 'Zapisz w telefonie'),
      );
      await tester.pumpAndSettle();
      expect(files.saved, ['Plan treningowy.pdf']);
    });

    testWidgets(
      'a photo opens full screen, zoomable and saved in the gallery',
      (tester) async {
        api.conversations['d'] = _direct('d', 'Ewa Lis');
        api.messages['d'] = [
          _message(
            '1',
            null,
            sender: 'x',
            name: 'Ewa Lis',
            attachments: const [_photo],
          ),
        ];
        await pump(
          tester,
          session: _client,
          initialLocation: '/client/messages/chat/d',
        );

        await tester.tap(find.byType(PhotoThumbnail));
        await tester.pumpAndSettle();
        expect(find.byType(PhotoViewerScreen), findsOneWidget);
        expect(find.byType(InteractiveViewer), findsOneWidget);
        // Who sent it, and when.
        expect(find.text('Ewa Lis'), findsOneWidget);
        expect(find.text('5 listopada, 10:30'), findsOneWidget);

        await tester.tap(find.byTooltip('Zapisz w galerii'));
        await tester.pumpAndSettle();
        expect(files.inGallery, ['/cache/photo-1/IMG_0042.jpg']);
        expect(find.text('Zapisano zdjęcie w galerii.'), findsOneWidget);
        expect(files.saved, isEmpty);
        await tester.tap(find.byTooltip('Otwórz w innej aplikacji'));
        await tester.pumpAndSettle();
        expect(files.opened, ['/cache/photo-1/IMG_0042.jpg']);
      },
    );

    testWidgets('a photo is shown by its thumbnail, whole full screen', (
      tester,
    ) async {
      const photo = ChatAttachment(
        id: 'photo-t',
        fileName: 'IMG_0100.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 900 * 1024,
        isImage: true,
        width: 1800,
        height: 2400,
        hasThumbnail: true,
      );
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api.messages['d'] = [
        _message(
          '1',
          null,
          sender: 'x',
          name: 'Ewa Lis',
          attachments: const [photo],
        ),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      final shown = tester.widget<Image>(
        find.descendant(
          of: find.byType(PhotoThumbnail),
          matching: find.byType(Image),
        ),
      );
      expect(
        ((shown.image as ResizeImage).imageProvider as FileImage).file.path,
        '/cache/thumbnails/photo-t.jpg',
      );

      await tester.tap(find.byType(PhotoThumbnail));
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-t/IMG_0100.jpg');
    });

    testWidgets('a photo the gallery does not take is saved as a file', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api.messages['d'] = [
        _message(
          '1',
          null,
          sender: 'x',
          name: 'Ewa Lis',
          attachments: const [_photo],
        ),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );
      await tester.tap(find.byType(PhotoThumbnail));
      await tester.pumpAndSettle();

      files.gallery = GallerySave.denied;
      await tester.tap(find.byTooltip('Zapisz w galerii'));
      await tester.pumpAndSettle();
      expect(find.textContaining('nie ma dostępu do zdjęć'), findsOneWidget);
      expect(files.saved, isEmpty);

      files.gallery = GallerySave.unsupported;
      await tester.tap(find.byTooltip('Zapisz w galerii'));
      await tester.pumpAndSettle();
      expect(files.saved, ['IMG_0042.jpg']);
    });

    testWidgets('a new photo below keeps every photo in its own message', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api.messages['d'] = [
        _message(
          '1',
          null,
          sender: 'x',
          name: 'Ewa Lis',
          attachments: const [_photo],
        ),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      api.messages['d']!.add(
        _message(
          '2',
          null,
          sender: 'x',
          name: 'Ewa Lis',
          attachments: const [
            ChatAttachment(
              id: 'photo-2',
              fileName: 'IMG_0043.jpg',
              contentType: 'image/jpeg',
              sizeBytes: 1024,
              isImage: true,
            ),
          ],
        ),
      );
      await tester.pump(messagesPollInterval);
      await tester.pumpAndSettle();

      final shown = [
        for (final image in tester.widgetList<Image>(
          find.descendant(
            of: find.byType(PhotoThumbnail),
            matching: find.byType(Image),
          ),
        ))
          ((image.image as ResizeImage).imageProvider as FileImage).file.path,
      ];
      expect(
        shown,
        unorderedEquals([
          '/cache/photo-1/IMG_0042.jpg',
          '/cache/photo-2/IMG_0043.jpg',
        ]),
      );
    });

    testWidgets('older messages load by themselves when scrolled up to', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api
        ..messages['d'] = [
          for (var i = 1; i <= 60; i++)
            _message('$i', 'Wiadomość $i', sender: 'x', name: 'Ewa Lis'),
        ]
        ..latestPageSize = 30
        ..olderFail = true;
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );
      expect(find.text('Wiadomość 60'), findsOneWidget);
      // The latest messages fill the screen: nothing more is loaded yet.
      expect(api.olderRequests, isEmpty);

      final messages = find.byType(Scrollable).first;
      final retry = find.text(
        'Nie udało się wczytać wcześniejszych wiadomości. Spróbuj ponownie',
      );
      await tester.scrollUntilVisible(retry, 300, scrollable: messages);
      await tester.pumpAndSettle();
      // A failure does not repeat on every scroll; the user tries again.
      expect(api.olderRequests, ['31']);
      await tester.drag(messages, const Offset(0, 100));
      await tester.pumpAndSettle();
      expect(api.olderRequests, ['31']);

      api.olderFail = false;
      await tester.tap(retry);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Wiadomość 1'),
        300,
        scrollable: messages,
      );
      expect(api.olderRequests, ['31', '31']);
      expect(retry, findsNothing);
    });

    testWidgets('swiping a photo goes through the photos of every message', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api
        ..messages['d'] = [
          _message(
            '1',
            null,
            sender: 'x',
            name: 'Ewa Lis',
            at: DateTime(2026, 11, 3, 9),
            attachments: [_photoNamed('a')],
          ),
          for (var i = 0; i < 40; i++)
            _message(
              't$i',
              'Wiadomość $i',
              sender: 'x',
              name: 'Ewa Lis',
              at: DateTime(2026, 11, 4, 8),
            ),
          _message(
            '2',
            'Moje ćwiczenia',
            sender: 'me',
            name: 'Anna Kowalska',
            isMine: true,
            at: DateTime(2026, 11, 4, 9),
            attachments: [_photoNamed('b'), _plan],
          ),
          _message(
            '3',
            null,
            sender: 'x',
            name: 'Ewa Lis',
            at: DateTime(2026, 11, 5, 9),
            attachments: [_photoNamed('c')],
          ),
        ]
        // The conversation opens without the oldest message, and the ones
        // loaded fill the screen.
        ..latestPageSize = 42;
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );
      expect(find.byType(PhotoThumbnail), findsNWidgets(2));
      expect(api.olderRequests, isEmpty);

      await tester.tap(_thumbnailOf('photo-c'));
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-c/c.jpg');
      // The photos before the ones loaded in the conversation are asked for.
      expect(api.attachmentRequests, ['photo before=photo-b']);

      // Older photos are to the left.
      await tester.fling(find.byType(PageView), const Offset(500, 0), 1500);
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-b/b.jpg');
      expect(find.text('Ty'), findsOneWidget);
      await tester.fling(find.byType(PageView), const Offset(500, 0), 1500);
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-a/a.jpg');
      expect(find.text('Ewa Lis'), findsOneWidget);
      // That is the first photo of the conversation.
      await tester.fling(find.byType(PageView), const Offset(500, 0), 1500);
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-a/a.jpg');
      expect(api.attachmentRequests, hasLength(1));

      await tester.fling(find.byType(PageView), const Offset(-500, 0), 1500);
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-b/b.jpg');
    });

    testWidgets('the details of a private chat lead to all photos and files', (
      tester,
    ) async {
      api.conversations['d'] = _direct('d', 'Ewa Lis');
      api.messages['d'] = [
        _message(
          '1',
          null,
          sender: 'x',
          name: 'Ewa Lis',
          at: DateTime(2024, 3, 10, 9),
          attachments: [_photoNamed('a')],
        ),
        _message(
          '2',
          'Plan na ten tydzień',
          sender: 'x',
          name: 'Ewa Lis',
          at: DateTime.now(),
          attachments: [_photoNamed('b'), _plan],
        ),
      ];
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/d',
      );

      await tester.tap(find.byTooltip('Informacje o czacie'));
      await tester.pumpAndSettle();
      expect(find.text('trener'), findsNWidgets(2));
      await tester.tap(find.text('Zdjęcia'));
      await tester.pumpAndSettle();

      // Newest first, by month.
      expect(find.byType(ConversationPhotosScreen), findsOneWidget);
      expect(find.text('Marzec 2024'), findsOneWidget);
      expect(find.byType(PhotoThumbnail), findsNWidgets(2));
      expect(
        tester.getTopLeft(_thumbnailOf('photo-b')).dy,
        lessThan(tester.getTopLeft(_thumbnailOf('photo-a')).dy),
      );

      // A photo opens among all of them in the grid's order: the next one,
      // to the right, is older.
      await tester.tap(_thumbnailOf('photo-b'));
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-b/b.jpg');
      await tester.fling(find.byType(PageView), const Offset(-500, 0), 1500);
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-a/a.jpg');
      await tester.fling(find.byType(PageView), const Offset(500, 0), 1500);
      await tester.pumpAndSettle();
      expect(_shownPhoto(tester), '/cache/photo-b/b.jpg');

      // Back in the details, the files.
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Pliki'));
      await tester.pumpAndSettle();
      expect(find.byType(ConversationFilesScreen), findsOneWidget);
      expect(find.text('Plan treningowy.pdf'), findsOneWidget);
      expect(find.textContaining('PDF · 1,4 MB · Ewa Lis'), findsOneWidget);
      await tester.tap(find.text('Plan treningowy.pdf'));
      await tester.pumpAndSettle();
      expect(files.opened, ['/cache/pdf-1/Plan treningowy.pdf']);
    });

    testWidgets('all photos are loaded page by page; files may be none', (
      tester,
    ) async {
      api.conversations['g'] = _group('g', 'Pole dance wt/czw');
      api
        ..messages['g'] = [
          for (final name in ['a', 'b', 'c', 'd', 'e'])
            _message(
              name,
              null,
              sender: 'x',
              name: 'Ewa Lis',
              attachments: [_photoNamed(name)],
            ),
        ]
        ..attachmentPageSize = 2;
      await pump(
        tester,
        session: _client,
        initialLocation: '/client/messages/chat/g',
      );
      await tester.tap(find.byTooltip('Informacje o czacie'));
      await tester.pumpAndSettle();
      // A group chat's details still list its people.
      expect(find.text('Członkowie · 2'), findsOneWidget);
      await tester.tap(find.text('Zdjęcia'));
      await tester.pumpAndSettle();

      // Two pages do not fill the screen, so the next ones follow.
      expect(find.byType(PhotoThumbnail), findsNWidgets(5));
      expect(api.attachmentRequests, [
        'photo before=null',
        'photo before=photo-d',
        'photo before=photo-b',
      ]);

      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Pliki'));
      await tester.pumpAndSettle();
      expect(
        find.text('W tej rozmowie nie ma jeszcze plików.'),
        findsOneWidget,
      );
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
