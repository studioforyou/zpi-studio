import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/data/open_conversation.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/widgets/chat_avatar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// How often new messages are fetched while the conversation is open.
const messagesPollInterval = Duration(seconds: 5);

/// A pause after which the next message starts a new chain, which shows its
/// time above it.
const messageChainGap = Duration(minutes: 10);

/// Longest message, as the backend accepts it.
const maxMessageLength = 2000;

/// One conversation: its messages, writing and, for a group or class chat,
/// its people and settings.
class ConversationScreen extends ConsumerStatefulWidget {
  const ConversationScreen({super.key, required this.conversationId});

  final String conversationId;

  @override
  ConsumerState<ConversationScreen> createState() => _ConversationScreenState();
}

class _ConversationScreenState extends ConsumerState<ConversationScreen> {
  final _input = TextEditingController();
  Timer? _timer;
  late final OpenConversation _open;
  StreamSubscription<String>? _arrived;
  Conversation? _conversation;
  List<ChatMessage> _messages = const [];
  bool _hasOlder = false;
  bool _loadingOlder = false;
  bool _sending = false;
  bool _polling = false;

  /// A poll was asked for while one was running, e.g. by a push.
  bool _pollAgain = false;
  Object? _error;
  String? _tappedId;

  /// Where the next poll starts looking for messages deleted by others.
  String? _deletedSince;

  ConversationsApi get _api => ref.read(conversationsApiProvider);

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(messagesPollInterval, (_) => _poll());
    // A push about a new message here shows it at once.
    _open = ref.read(openConversationProvider)..id = widget.conversationId;
    _arrived = _open.arrived
        .where((id) => id == widget.conversationId)
        .listen((_) => _poll());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _arrived?.cancel();
    if (_open.id == widget.conversationId) {
      _open.id = null;
    }
    _input.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final conversation = await _api.fetchConversation(widget.conversationId);
      final page = await _api.fetchMessages(widget.conversationId);
      if (!mounted) {
        return;
      }
      setState(() {
        _conversation = conversation;
        _messages = page.items;
        _hasOlder = page.hasMore;
        _deletedSince = page.nextDeletedSince;
      });
      _markRead();
    } catch (e) {
      if (mounted) {
        setState(() => _error = e);
      }
    }
  }

  /// Fetches messages newer than the last one, the ones deleted since the last
  /// poll and the conversation's current settings and membership.
  Future<void> _poll() async {
    if (_conversation == null || !mounted) {
      return;
    }
    if (_polling) {
      _pollAgain = true;
      return;
    }
    _polling = true;
    try {
      final conversation = await _api.fetchConversation(widget.conversationId);
      final after = _messages.lastOrNull?.id;
      final page = await _api.fetchMessages(
        widget.conversationId,
        after: after,
        deletedSince: _deletedSince,
      );
      if (!mounted) {
        return;
      }
      final known = {for (final message in _messages) message.id};
      final fresh = [
        for (final message in page.items)
          if (!known.contains(message.id)) message,
      ];
      final deleted = page.deletedIds.toSet();
      setState(() {
        _conversation = conversation;
        _messages = [
          for (final message in _messages)
            deleted.contains(message.id) && !message.isDeleted
                ? _deleted(message)
                : message,
          ...fresh,
        ];
        _deletedSince = page.nextDeletedSince ?? _deletedSince;
      });
      if (fresh.any((message) => !message.isMine)) {
        _markRead();
      }
    } catch (_) {
      // The next poll tries again.
    } finally {
      _polling = false;
      if (_pollAgain) {
        _pollAgain = false;
        unawaited(_poll());
      }
    }
  }

  Future<void> _loadOlder() async {
    final first = _messages.firstOrNull;
    if (first == null || _loadingOlder) {
      return;
    }
    setState(() => _loadingOlder = true);
    try {
      final page = await _api.fetchMessages(
        widget.conversationId,
        before: first.id,
      );
      if (mounted) {
        setState(() {
          _messages = [...page.items, ..._messages];
          _hasOlder = page.hasMore;
        });
      }
    } catch (e) {
      _showError(e);
    } finally {
      if (mounted) {
        setState(() => _loadingOlder = false);
      }
    }
  }

  void _markRead() {
    _api.markRead(widget.conversationId).then((_) {
      final session = switch (ref.read(authControllerProvider)) {
        Authenticated(:final session) => session,
        _ => null,
      };
      if (session != null && mounted) {
        ref.invalidate(unreadMessagesProvider(session.accountId));
      }
    }, onError: (_) {});
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty || _sending) {
      return;
    }
    setState(() => _sending = true);
    try {
      final message = await _api.send(widget.conversationId, text);
      if (!mounted) {
        return;
      }
      _input.clear();
      setState(() {
        if (!_messages.any((known) => known.id == message.id)) {
          _messages = [..._messages, message];
        }
      });
    } catch (e) {
      _showError(e, sending: true);
      unawaited(_poll());
    } finally {
      if (mounted) {
        setState(() => _sending = false);
      }
    }
  }

  Future<void> _showMessageMenu(ChatMessage message, Offset position) async {
    final l10n = AppLocalizations.of(context);
    final overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
    final action = await showMenu<String>(
      context: context,
      position: RelativeRect.fromRect(
        position & const Size(1, 1),
        Offset.zero & overlay.size,
      ),
      items: [
        if (!message.isDeleted)
          PopupMenuItem(
            value: 'copy',
            child: ListTile(
              leading: const Icon(Icons.content_copy),
              title: Text(l10n.chatCopy),
            ),
          ),
        if (message.canDelete)
          PopupMenuItem(
            value: 'delete',
            child: ListTile(
              leading: Icon(
                Icons.delete,
                color: Theme.of(context).colorScheme.error,
              ),
              title: Text(
                l10n.chatDeleteMessage,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          ),
      ],
    );
    if (!mounted) {
      return;
    }
    switch (action) {
      case 'copy':
        await Clipboard.setData(ClipboardData(text: message.body ?? ''));
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(l10n.chatCopied)));
        }
      case 'delete':
        try {
          await _api.deleteMessage(widget.conversationId, message.id);
          if (mounted) {
            setState(() {
              _messages = [
                for (final known in _messages)
                  known.id == message.id ? _deleted(known) : known,
              ];
            });
          }
        } catch (e) {
          _showError(e);
        }
    }
  }

  Future<void> _hide() async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.chatHideTitle),
        content: Text(l10n.chatHideText),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l10n.chatCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(l10n.chatHide),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    try {
      await _api.hide(widget.conversationId);
      if (mounted) {
        ref.invalidate(conversationsProvider);
        context.pop();
      }
    } catch (e) {
      _showError(e);
    }
  }

  Future<void> _showInfo() async {
    final conversation = _conversation;
    if (conversation == null) {
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (_) => _InfoSheet(
        conversation: conversation,
        accountId: _accountId(),
        onClientsCanReply: (value) async {
          try {
            final updated = await _api.setClientsCanReply(
              conversation.id,
              value,
            );
            if (mounted) {
              setState(() => _conversation = updated);
            }
            return updated;
          } catch (e) {
            _showError(e);
            return null;
          }
        },
      ),
    );
  }

  String? _accountId() => switch (ref.read(authControllerProvider)) {
    Authenticated(:final session) => session.accountId,
    _ => null,
  };

  void _showError(Object error, {bool sending = false}) {
    if (!mounted) {
      return;
    }
    final l10n = AppLocalizations.of(context);
    final status = error is ApiException ? error.statusCode : null;
    final message = sending && status != 404 && status != 422
        ? l10n.messagesSendError
        : messagesErrorMessage(error, l10n);
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final conversation = _conversation;
    final error = _error;
    if (conversation == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(
          child: error == null
              ? const CircularProgressIndicator()
              : Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    spacing: 16,
                    children: [
                      const Icon(Icons.error_outline),
                      Text(
                        messagesErrorMessage(error, l10n, loading: true),
                        textAlign: TextAlign.center,
                      ),
                      OutlinedButton.icon(
                        onPressed: _load,
                        icon: const Icon(Icons.refresh),
                        label: Text(l10n.messagesRetry),
                      ),
                    ],
                  ),
                ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        shape: Border(
          bottom: BorderSide(
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
        ),
        title: Row(
          spacing: 10,
          children: [
            ConversationAvatar(
              kind: conversation.kind,
              title: conversation.title,
              counterpart: conversation.counterpart,
              size: 40,
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    conversation.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  Text(
                    _subtitle(conversation, l10n),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          if (conversation.isGroup)
            IconButton(
              tooltip: l10n.chatInfo,
              onPressed: _showInfo,
              icon: const Icon(Icons.info_outline),
            ),
          if (conversation.canHide)
            PopupMenuButton<void>(
              itemBuilder: (_) => [
                PopupMenuItem(onTap: _hide, child: Text(l10n.chatHide)),
              ],
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(child: _messageList(conversation, l10n)),
          if (conversation.canWrite)
            _InputBar(controller: _input, sending: _sending, onSend: _send)
          else
            _Banner(
              icon: conversation.hasLeft || conversation.isClosed
                  ? Icons.info
                  : Icons.campaign,
              text: conversation.hasLeft
                  ? l10n.chatLeftBanner
                  : conversation.isClosed
                  ? l10n.chatClosedBanner
                  : l10n.chatReadOnlyBanner,
            ),
        ],
      ),
    );
  }

  Widget _messageList(Conversation conversation, AppLocalizations l10n) {
    if (_messages.isEmpty) {
      return Center(child: Text(l10n.chatEmpty));
    }
    final now = DateTime.now();
    final count = _messages.length + (_hasOlder ? 1 : 0);
    return ListView.builder(
      reverse: true,
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      itemCount: count,
      itemBuilder: (context, index) {
        if (index == _messages.length) {
          return Center(
            child: TextButton(
              onPressed: _loadingOlder ? null : _loadOlder,
              child: Text(l10n.chatLoadOlder),
            ),
          );
        }
        final position = _messages.length - 1 - index;
        final message = _messages[position];
        final previous = position > 0 ? _messages[position - 1] : null;
        final next = position < _messages.length - 1
            ? _messages[position + 1]
            : null;
        final newDay =
            previous == null ||
            !isSameDay(previous.createdAt, message.createdAt);
        final startsChain =
            newDay ||
            message.createdAt.difference(previous.createdAt) >= messageChainGap;
        final startsRun =
            newDay || previous.senderAccountId != message.senderAccountId;
        final endsRun =
            next == null ||
            !isSameDay(next.createdAt, message.createdAt) ||
            next.senderAccountId != message.senderAccountId;
        return _MessageRow(
          message: message,
          showName: conversation.isGroup && !message.isMine && startsRun,
          showAvatar: !message.isMine && endsRun,
          topGap: previous == null ? 0 : (startsChain || startsRun ? 14 : 3),
          startsChain: startsChain,
          timeLabel: formatChainTime(message.createdAt, l10n, now),
          tapped: _tappedId == message.id,
          onTap: () => setState(
            () => _tappedId = _tappedId == message.id ? null : message.id,
          ),
          onLongPress: (position) => _showMessageMenu(message, position),
        );
      },
    );
  }

  String _subtitle(Conversation conversation, AppLocalizations l10n) {
    if (!conversation.isGroup) {
      // A trainer writes to a trainee, a client to a trainer.
      final isTrainer = switch (ref.read(authControllerProvider)) {
        Authenticated(:final session) => session.activeRole == AppRole.trainer,
        _ => false,
      };
      return isTrainer ? l10n.chatTrainee : l10n.chatTrainer;
    }
    if (conversation.hasLeft) {
      return l10n.chatLeftBanner;
    }
    return [
      l10n.chatMembers(conversation.members.length),
      if (conversation.clientsCanReply)
        l10n.chatClientsCanReply
      else
        l10n.chatOnlyLeaders,
    ].join(' · ');
  }

  static ChatMessage _deleted(ChatMessage message) => ChatMessage(
    id: message.id,
    senderAccountId: message.senderAccountId,
    senderName: message.senderName,
    isMine: message.isMine,
    body: null,
    isDeleted: true,
    createdAt: message.createdAt,
    canDelete: false,
  );
}

class _MessageRow extends StatelessWidget {
  const _MessageRow({
    required this.message,
    required this.showName,
    required this.showAvatar,
    required this.topGap,
    required this.startsChain,
    required this.timeLabel,
    required this.tapped,
    required this.onTap,
    required this.onLongPress,
  });

  final ChatMessage message;

  /// Whether the sender's name is shown above the bubble: the first message
  /// of a run of someone else's messages in a group chat.
  final bool showName;

  /// Whether the sender's avatar is shown next to the bubble: the last message
  /// of a run of someone else's messages.
  final bool showAvatar;

  /// Space above the message; larger between different people's messages.
  final double topGap;

  /// Whether the message is the first of a chain (after a new day or a pause
  /// of [messageChainGap]), whose day and time are always shown above it.
  final bool startsChain;

  /// The day and time shown above the message, e.g. `wczoraj, 18:30`.
  final String timeLabel;

  /// Whether the user tapped the message, which shows its day and time above
  /// it.
  final bool tapped;
  final VoidCallback onTap;
  final ValueChanged<Offset> onLongPress;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final mine = message.isMine;
    final background = message.isDeleted
        ? Colors.transparent
        : mine
        ? (tapped
              ? Color.lerp(scheme.primary, Colors.black, 0.2)!
              : scheme.primary)
        : (tapped
              ? scheme.surfaceContainerHighest
              : scheme.surfaceContainerHigh);
    final foreground = message.isDeleted
        ? scheme.outline
        : (mine ? scheme.onPrimary : scheme.onSurface);
    const radius = Radius.circular(18);
    const tail = Radius.circular(6);

    final bubble = GestureDetector(
      onTap: onTap,
      onLongPressStart: (details) => onLongPress(details.globalPosition),
      child: Container(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * 0.74,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(
          color: background,
          border: message.isDeleted
              ? Border.all(color: scheme.outlineVariant)
              : null,
          borderRadius: BorderRadius.only(
            topLeft: radius,
            topRight: radius,
            bottomLeft: mine ? radius : tail,
            bottomRight: mine ? tail : radius,
          ),
        ),
        child: Text(
          message.isDeleted ? l10n.messagesDeleted : message.body ?? '',
          style: theme.textTheme.bodyLarge?.copyWith(
            color: foreground,
            fontStyle: message.isDeleted ? FontStyle.italic : null,
          ),
        ),
      ),
    );

    return Padding(
      padding: EdgeInsets.only(top: topGap),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (startsChain || tapped)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                timeLabel,
                textAlign: TextAlign.center,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
          if (showName)
            Padding(
              padding: const EdgeInsets.only(left: 52, bottom: 3),
              child: Text(
                message.senderName,
                style: theme.textTheme.labelMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
          Row(
            mainAxisAlignment: mine
                ? MainAxisAlignment.end
                : MainAxisAlignment.start,
            crossAxisAlignment: CrossAxisAlignment.end,
            spacing: 8,
            children: [
              if (!mine)
                showAvatar
                    ? PersonAvatar(
                        name: message.senderName,
                        seed: message.senderAccountId,
                        size: 32,
                      )
                    : const SizedBox(width: 32),
              bubble,
            ],
          ),
        ],
      ),
    );
  }
}

class _InputBar extends StatelessWidget {
  const _InputBar({
    required this.controller,
    required this.sending,
    required this.onSend,
  });

  final TextEditingController controller;
  final bool sending;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 8, 12),
        child: Row(
          spacing: 8,
          children: [
            Expanded(
              child: TextField(
                controller: controller,
                minLines: 1,
                maxLines: 5,
                maxLength: maxMessageLength,
                textCapitalization: TextCapitalization.sentences,
                decoration: InputDecoration(
                  hintText: l10n.chatInputHint,
                  counterText: '',
                  filled: true,
                  fillColor: scheme.surfaceContainerHigh,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 18,
                    vertical: 12,
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(24),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
            IconButton.filled(
              tooltip: l10n.chatSend,
              onPressed: sending ? null : onSend,
              iconSize: 24,
              style: IconButton.styleFrom(fixedSize: const Size(48, 48)),
              icon: const Icon(Icons.send),
            ),
          ],
        ),
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      child: Container(
        margin: const EdgeInsets.fromLTRB(12, 4, 12, 14),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: scheme.surfaceContainer,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          spacing: 12,
          children: [
            Icon(icon, size: 20, color: scheme.primary),
            Flexible(child: Text(text, textAlign: TextAlign.center)),
          ],
        ),
      ),
    );
  }
}

/// The people and settings of a group or class chat.
class _InfoSheet extends StatefulWidget {
  const _InfoSheet({
    required this.conversation,
    required this.accountId,
    required this.onClientsCanReply,
  });

  final Conversation conversation;
  final String? accountId;
  final Future<Conversation?> Function(bool value) onClientsCanReply;

  @override
  State<_InfoSheet> createState() => _InfoSheetState();
}

class _InfoSheetState extends State<_InfoSheet> {
  /// Members shown before "show all".
  static const _collapsedMembers = 5;

  late Conversation _conversation = widget.conversation;
  bool _showAll = false;
  bool _saving = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final members = _showAll
        ? _conversation.members
        : _conversation.members.take(_collapsedMembers).toList();
    final section = theme.textTheme.labelLarge?.copyWith(
      color: theme.colorScheme.primary,
    );

    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.8,
        ),
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.only(bottom: 16),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text(
                _conversation.title,
                style: theme.textTheme.titleLarge,
              ),
            ),
            if (_conversation.isLeader)
              SwitchListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 24),
                title: Text(l10n.newChatClientsReply),
                subtitle: Text(l10n.chatRepliesHint),
                value: _conversation.clientsCanReply,
                onChanged: _saving
                    ? null
                    : (value) async {
                        setState(() => _saving = true);
                        final updated = await widget.onClientsCanReply(value);
                        if (mounted) {
                          setState(() {
                            _saving = false;
                            if (updated != null) {
                              _conversation = updated;
                            }
                          });
                        }
                      },
              ),
            const Divider(),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 4),
              child: Text(l10n.chatLeaders, style: section),
            ),
            for (final person in _conversation.leaders) _person(person, l10n),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 4),
              child: Text(
                l10n.chatMembersHeader(_conversation.members.length),
                style: section,
              ),
            ),
            for (final person in members) _person(person, l10n),
            if (!_showAll && _conversation.members.length > _collapsedMembers)
              ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 24),
                leading: const Icon(Icons.expand_more),
                title: Text(l10n.chatShowAll),
                onTap: () => setState(() => _showAll = true),
              ),
          ],
        ),
      ),
    );
  }

  Widget _person(ChatPerson person, AppLocalizations l10n) => ListTile(
    dense: true,
    contentPadding: const EdgeInsets.symmetric(horizontal: 24),
    leading: PersonAvatar(
      name: person.displayName,
      seed: person.accountId,
      size: 32,
    ),
    title: Text(
      person.accountId == widget.accountId
          ? l10n.chatYou(person.displayName)
          : person.displayName,
      style: Theme.of(context).textTheme.bodyLarge,
    ),
  );
}
