import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// The signed-in user's conversations (`OwnConversationController` in the
/// backend).
const conversationsPath = '/api/v1/me/conversations';

enum ConversationKind {
  /// A 1:1 conversation between a client and a trainer.
  direct,

  /// The chat of a fixed group.
  group,

  /// The chat of one class or workshop.
  event;

  static ConversationKind fromApi(String value) => switch (value) {
    'GROUP' => group,
    'EVENT' => event,
    _ => direct,
  };
}

/// A person in a conversation, by their account.
class ChatPerson {
  const ChatPerson({required this.accountId, required this.displayName});

  factory ChatPerson.fromJson(Map<String, dynamic> json) => ChatPerson(
    accountId: json['accountId'] as String,
    displayName: json['displayName'] as String,
  );

  final String accountId;
  final String displayName;
}

/// The latest message of a conversation, as shown in the list.
class MessagePreview {
  const MessagePreview({
    required this.senderName,
    required this.isMine,
    required this.body,
    required this.isDeleted,
    required this.createdAt,
  });

  factory MessagePreview.fromJson(Map<String, dynamic> json) => MessagePreview(
    senderName: json['senderName'] as String?,
    isMine: json['isMine'] as bool,
    body: json['body'] as String?,
    isDeleted: json['isDeleted'] as bool,
    createdAt: DateTime.parse(json['createdAt'] as String).toLocal(),
  );

  /// `null` for the user's own message.
  final String? senderName;
  final bool isMine;

  /// `null` for a deleted message.
  final String? body;
  final bool isDeleted;
  final DateTime createdAt;
}

/// A conversation in the user's list.
class ConversationSummary {
  const ConversationSummary({
    required this.id,
    required this.kind,
    required this.title,
    required this.counterpart,
    required this.eventStartsAt,
    required this.hasLeft,
    required this.isClosed,
    required this.lastMessage,
    required this.unreadCount,
  });

  factory ConversationSummary.fromJson(Map<String, dynamic> json) {
    final counterpart = json['counterpart'] as Map<String, dynamic>?;
    final last = json['lastMessage'] as Map<String, dynamic>?;
    final startsAt = json['eventStartsAt'] as String?;
    return ConversationSummary(
      id: json['id'] as String,
      kind: ConversationKind.fromApi(json['kind'] as String),
      title: json['title'] as String,
      counterpart: counterpart == null
          ? null
          : ChatPerson.fromJson(counterpart),
      eventStartsAt: startsAt == null
          ? null
          : DateTime.parse(startsAt).toLocal(),
      hasLeft: json['hasLeft'] as bool,
      isClosed: json['isClosed'] as bool,
      lastMessage: last == null ? null : MessagePreview.fromJson(last),
      unreadCount: (json['unreadCount'] as num).toInt(),
    );
  }

  final String id;
  final ConversationKind kind;

  /// The other person's name in a 1:1 conversation, otherwise the chat's
  /// title.
  final String title;

  /// The other person of a 1:1 conversation.
  final ChatPerson? counterpart;

  /// Start of the class of a class chat.
  final DateTime? eventStartsAt;

  /// Whether the user no longer belongs to the group or class.
  final bool hasLeft;

  /// Whether the class of a class chat has ended or was cancelled.
  final bool isClosed;
  final MessagePreview? lastMessage;
  final int unreadCount;
}

/// A conversation opened by the user, with what they may do in it.
class Conversation {
  const Conversation({
    required this.id,
    required this.kind,
    required this.title,
    required this.counterpart,
    required this.clientsCanReply,
    required this.hasLeft,
    required this.isClosed,
    required this.isLeader,
    required this.canWrite,
    required this.canHide,
    required this.leaders,
    required this.members,
  });

  factory Conversation.fromJson(Map<String, dynamic> json) {
    final counterpart = json['counterpart'] as Map<String, dynamic>?;
    return Conversation(
      id: json['id'] as String,
      kind: ConversationKind.fromApi(json['kind'] as String),
      title: json['title'] as String,
      counterpart: counterpart == null
          ? null
          : ChatPerson.fromJson(counterpart),
      clientsCanReply: json['clientsCanReply'] as bool,
      hasLeft: json['hasLeft'] as bool,
      isClosed: json['isClosed'] as bool,
      isLeader: json['isLeader'] as bool,
      canWrite: json['canWrite'] as bool,
      canHide: json['canHide'] as bool,
      leaders: _people(json['leaders']),
      members: _people(json['members']),
    );
  }

  final String id;
  final ConversationKind kind;
  final String title;
  final ChatPerson? counterpart;
  final bool clientsCanReply;
  final bool hasLeft;
  final bool isClosed;

  /// Whether the user runs the group or class: they always write, delete
  /// other people's messages and change whether clients may reply.
  final bool isLeader;
  final bool canWrite;

  /// Whether the user may remove the chat from their list.
  final bool canHide;

  /// The staff running the group or class.
  final List<ChatPerson> leaders;

  /// The clients in the chat; both people of a 1:1 conversation.
  final List<ChatPerson> members;

  bool get isGroup => kind != ConversationKind.direct;
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.senderAccountId,
    required this.senderName,
    required this.isMine,
    required this.body,
    required this.isDeleted,
    required this.createdAt,
    required this.canDelete,
  });

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
    id: json['id'] as String,
    senderAccountId: json['senderAccountId'] as String,
    senderName: json['senderName'] as String,
    isMine: json['isMine'] as bool,
    body: json['body'] as String?,
    isDeleted: json['isDeleted'] as bool,
    createdAt: DateTime.parse(json['createdAt'] as String).toLocal(),
    canDelete: json['canDelete'] as bool,
  );

  final String id;
  final String senderAccountId;
  final String senderName;
  final bool isMine;

  /// `null` for a deleted message.
  final String? body;
  final bool isDeleted;
  final DateTime createdAt;
  final bool canDelete;
}

/// Messages oldest first.
class MessagePage {
  const MessagePage({
    required this.items,
    required this.hasMore,
    this.deletedIds = const [],
    this.nextDeletedSince,
  });

  final List<ChatMessage> items;

  /// Whether there are older messages (or newer ones, when polling).
  final bool hasMore;

  /// Messages deleted since the `deletedSince` passed in the request.
  final List<String> deletedIds;

  /// The `deletedSince` to pass in the next poll.
  final String? nextDeletedSince;
}

/// A client a trainer can write to: assigned to the trainer, in one of their
/// fixed groups or booked on one of their classes.
class MessageableClient {
  const MessageableClient({
    required this.customerId,
    required this.displayName,
    required this.isChild,
    required this.isTrainee,
    required this.classes,
    required this.conversationId,
  });

  factory MessageableClient.fromJson(Map<String, dynamic> json) =>
      MessageableClient(
        customerId: json['customerId'] as String,
        displayName: json['displayName'] as String,
        isChild: json['isChild'] as bool,
        isTrainee: json['isTrainee'] as bool,
        classes: [
          for (final name in json['classes'] as List? ?? const [])
            name as String,
        ],
        conversationId: json['conversationId'] as String?,
      );

  final String customerId;
  final String displayName;
  final bool isChild;

  /// Whether the client trains with the trainer personally.
  final bool isTrainee;

  /// The trainer's groups and classes the client takes part in.
  final List<String> classes;

  /// The trainer's existing conversation with the client (for a child, with
  /// the guardian).
  final String? conversationId;
}

/// A trainer a client can write to.
class MessageableTrainer {
  const MessageableTrainer({
    required this.staffId,
    required this.displayName,
    required this.conversationId,
  });

  factory MessageableTrainer.fromJson(Map<String, dynamic> json) =>
      MessageableTrainer(
        staffId: json['staffId'] as String,
        displayName: json['displayName'] as String,
        conversationId: json['conversationId'] as String?,
      );

  final String staffId;
  final String displayName;

  /// The client's existing conversation with the trainer.
  final String? conversationId;
}

/// A fixed group the trainer runs.
class GroupChatTarget {
  const GroupChatTarget({
    required this.trainingGroupId,
    required this.name,
    required this.memberCount,
    required this.conversationId,
  });

  factory GroupChatTarget.fromJson(Map<String, dynamic> json) =>
      GroupChatTarget(
        trainingGroupId: json['trainingGroupId'] as String,
        name: json['name'] as String,
        memberCount: (json['memberCount'] as num).toInt(),
        conversationId: json['conversationId'] as String?,
      );

  final String trainingGroupId;
  final String name;
  final int memberCount;

  /// The group's chat, when it already has one.
  final String? conversationId;
}

/// An upcoming class the trainer runs.
class EventChatTarget {
  const EventChatTarget({
    required this.eventId,
    required this.title,
    required this.startsAt,
    required this.participantCount,
    required this.conversationId,
  });

  factory EventChatTarget.fromJson(Map<String, dynamic> json) =>
      EventChatTarget(
        eventId: json['eventId'] as String,
        title: json['title'] as String,
        startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
        participantCount: (json['participantCount'] as num).toInt(),
        conversationId: json['conversationId'] as String?,
      );

  final String eventId;
  final String title;
  final DateTime startsAt;
  final int participantCount;

  /// The class's chat, when it already has one.
  final String? conversationId;
}

class GroupChatTargets {
  const GroupChatTargets({required this.groups, required this.events});

  final List<GroupChatTarget> groups;
  final List<EventChatTarget> events;
}

class ConversationsApi {
  const ConversationsApi(this._dio);

  final Dio _dio;

  /// The user's conversations, most recently active first.
  Future<List<ConversationSummary>> fetchConversations() => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(conversationsPath);
    return [
      for (final item in response.data!['items'] as List)
        ConversationSummary.fromJson(item as Map<String, dynamic>),
    ];
  });

  Future<int> fetchUnreadCount() => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$conversationsPath/unread-count',
    );
    return (response.data!['count'] as num).toInt();
  });

  Future<Conversation> fetchConversation(String id) => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$conversationsPath/$id',
    );
    return Conversation.fromJson(response.data!);
  });

  /// The latest messages, the ones [before] a message or the ones [after]
  /// one. With [deletedSince] (the previous page's
  /// [MessagePage.nextDeletedSince]) the page also lists the messages deleted
  /// since then.
  Future<MessagePage> fetchMessages(
    String conversationId, {
    String? before,
    String? after,
    String? deletedSince,
  }) => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$conversationsPath/$conversationId/messages',
      queryParameters: {
        'before': ?before,
        'after': ?after,
        'deletedSince': ?deletedSince,
      },
    );
    final data = response.data!;
    return MessagePage(
      items: [
        for (final item in data['items'] as List)
          ChatMessage.fromJson(item as Map<String, dynamic>),
      ],
      hasMore: data['hasMore'] as bool,
      deletedIds: [
        for (final id in data['deletedIds'] as List? ?? const []) id as String,
      ],
      nextDeletedSince: data['nextDeletedSince'] as String?,
    );
  });

  /// The trainer's clients they can write to, sorted by name.
  Future<List<MessageableClient>> fetchClients() => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$conversationsPath/clients',
    );
    return [
      for (final item in response.data!['items'] as List)
        MessageableClient.fromJson(item as Map<String, dynamic>),
    ];
  });

  Future<ChatMessage> send(String conversationId, String body) =>
      _call(() async {
        final response = await _dio.post<Map<String, dynamic>>(
          '$conversationsPath/$conversationId/messages',
          data: {'body': body},
        );
        return ChatMessage.fromJson(response.data!);
      });

  Future<void> deleteMessage(String conversationId, String messageId) =>
      _call(() async {
        await _dio.delete<void>(
          '$conversationsPath/$conversationId/messages/$messageId',
        );
      });

  Future<void> markRead(String conversationId) => _call(() async {
    await _dio.post<void>('$conversationsPath/$conversationId/read');
  });

  Future<Conversation> setClientsCanReply(String conversationId, bool value) =>
      _call(() async {
        final response = await _dio.patch<Map<String, dynamic>>(
          '$conversationsPath/$conversationId',
          data: {'clientsCanReply': value},
        );
        return Conversation.fromJson(response.data!);
      });

  /// Removes a chat the user no longer belongs to from their list.
  Future<void> hide(String conversationId) => _call(() async {
    await _dio.delete<void>('$conversationsPath/$conversationId');
  });

  /// Opens the trainer's conversation with one of their clients, creating it
  /// if needed.
  Future<Conversation> startWithTrainee(String customerId) => _call(() async {
    final response = await _dio.post<Map<String, dynamic>>(
      '$conversationsPath/trainee',
      data: {'customerId': customerId},
    );
    return Conversation.fromJson(response.data!);
  });

  /// Opens the client's conversation with a trainer, creating it if needed.
  Future<Conversation> startWithTrainer(String staffId) => _call(() async {
    final response = await _dio.post<Map<String, dynamic>>(
      '$conversationsPath/trainer',
      data: {'staffId': staffId},
    );
    return Conversation.fromJson(response.data!);
  });

  Future<List<MessageableTrainer>> fetchTrainers() => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$conversationsPath/trainers',
    );
    return [
      for (final item in response.data!['items'] as List)
        MessageableTrainer.fromJson(item as Map<String, dynamic>),
    ];
  });

  Future<GroupChatTargets> fetchGroupChatTargets() => _call(() async {
    final response = await _dio.get<Map<String, dynamic>>(
      '$conversationsPath/group-chat-targets',
    );
    final data = response.data!;
    return GroupChatTargets(
      groups: [
        for (final item in data['groups'] as List)
          GroupChatTarget.fromJson(item as Map<String, dynamic>),
      ],
      events: [
        for (final item in data['events'] as List)
          EventChatTarget.fromJson(item as Map<String, dynamic>),
      ],
    );
  });

  /// Creates the chat of a fixed group ([trainingGroupId]) or a class
  /// ([eventId]).
  Future<Conversation> createGroupChat({
    String? trainingGroupId,
    String? eventId,
    required String title,
    required bool clientsCanReply,
  }) => _call(() async {
    final response = await _dio.post<Map<String, dynamic>>(
      '$conversationsPath/group-chats',
      data: {
        'trainingGroupId': ?trainingGroupId,
        'eventId': ?eventId,
        'title': title,
        'clientsCanReply': clientsCanReply,
      },
    );
    return Conversation.fromJson(response.data!);
  });

  static Future<T> _call<T>(Future<T> Function() request) async {
    try {
      return await request();
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

List<ChatPerson> _people(Object? json) => [
  for (final item in json as List? ?? const [])
    ChatPerson.fromJson(item as Map<String, dynamic>),
];

final conversationsApiProvider = Provider<ConversationsApi>(
  (ref) => ConversationsApi(ref.watch(dioProvider)),
);

/// The user's conversations. The account id keeps one account's
/// conversations from being shown to another account.
final conversationsProvider = FutureProvider.autoDispose
    .family<List<ConversationSummary>, String>(
      (ref, accountId) =>
          ref.watch(conversationsApiProvider).fetchConversations(),
    );

/// How often the unread counter on the messages tab is refreshed.
const unreadRefreshInterval = Duration(seconds: 30);

/// Unread messages of the signed-in account, refreshed every
/// [unreadRefreshInterval]. A failed refresh keeps the last known count.
final unreadMessagesProvider = StreamProvider.autoDispose.family<int, String>((
  ref,
  accountId,
) {
  final api = ref.watch(conversationsApiProvider);
  final controller = StreamController<int>();
  Future<void> load() async {
    try {
      controller.add(await api.fetchUnreadCount());
    } catch (_) {
      // The counter is a hint; the next refresh tries again.
    }
  }

  unawaited(load());
  final timer = Timer.periodic(unreadRefreshInterval, (_) => load());
  ref.onDispose(() {
    timer.cancel();
    controller.close();
  });
  return controller.stream;
});

final messageableTrainersProvider = FutureProvider.autoDispose
    .family<List<MessageableTrainer>, String>(
      (ref, accountId) => ref.watch(conversationsApiProvider).fetchTrainers(),
    );

final messageableClientsProvider = FutureProvider.autoDispose
    .family<List<MessageableClient>, String>(
      (ref, accountId) => ref.watch(conversationsApiProvider).fetchClients(),
    );

final groupChatTargetsProvider = FutureProvider.autoDispose
    .family<GroupChatTargets, String>(
      (ref, accountId) =>
          ref.watch(conversationsApiProvider).fetchGroupChatTargets(),
    );
