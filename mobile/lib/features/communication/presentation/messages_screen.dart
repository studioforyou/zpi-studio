import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/notifications_screen.dart';
import 'package:mobile/features/communication/presentation/widgets/chat_avatar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The conversation screen, pushed above the role's shell.
String conversationLocation(AppRole role, String conversationId) =>
    '/${role.name}/messages/chat/$conversationId';

/// The picker of a trainee to write to.
const traineePickerLocation = '/trainer/messages/to-trainee';

/// The picker of a trainer to write to.
const trainerPickerLocation = '/client/messages/to-trainer';

/// The form of a new chat of a fixed group or a class.
const newGroupChatLocation = '/trainer/messages/new-chat';

/// Unread messages and notifications together, for the messages tab badge.
/// A notification about new messages is not counted again: its messages are.
int watchUnreadInbox(WidgetRef ref) {
  final auth = ref.watch(authControllerProvider);
  if (auth is! Authenticated) return 0;
  final messages =
      ref.watch(unreadMessagesProvider(auth.session.accountId)).value ?? 0;
  return watchUnreadOtherNotifications(ref) + messages;
}

/// How often the list is refreshed while it is shown.
const listRefreshInterval = Duration(seconds: 15);

enum _Filter { all, private, group }

/// The signed-in user's conversations.
class MessagesScreen extends ConsumerStatefulWidget {
  const MessagesScreen({super.key});

  @override
  ConsumerState<MessagesScreen> createState() => _MessagesScreenState();
}

class _MessagesScreenState extends ConsumerState<MessagesScreen> {
  _Filter _filter = _Filter.all;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(listRefreshInterval, (_) {
      final session = _session();
      if (session != null && mounted) {
        ref.invalidate(conversationsProvider(session.accountId));
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Session? _session() => switch (ref.read(authControllerProvider)) {
    Authenticated(:final session) => session,
    _ => null,
  };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    if (session == null) {
      return const SizedBox();
    }
    final isTrainer = session.activeRole == AppRole.trainer;
    final provider = conversationsProvider(session.accountId);
    final unreadNotifications = watchUnreadNotifications(ref);

    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.navMessages),
        actions: [
          IconButton(
            tooltip: l10n.notificationsOpen,
            icon: Badge(
              isLabelVisible: unreadNotifications > 0,
              label: Text('$unreadNotifications'),
              child: const Icon(Icons.notifications_outlined),
            ),
            onPressed: () => context.push(
              isTrainer ? trainerNotificationsPath : clientNotificationsPath,
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => isTrainer
            ? _chooseNewMessage(context, session)
            : _startConversation(context, session, trainerPickerLocation),
        icon: const Icon(Icons.edit),
        label: Text(isTrainer ? l10n.messagesNew : l10n.messagesWriteToTrainer),
      ),
      body: ref
          .watch(provider)
          .when(
            skipLoadingOnRefresh: true,
            skipLoadingOnReload: true,
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (error, _) => _LoadError(
              message: messagesErrorMessage(error, l10n, loading: true),
              onRetry: () => ref.invalidate(provider),
            ),
            data: (conversations) {
              if (conversations.isEmpty) {
                return _Empty(
                  text: isTrainer
                      ? l10n.messagesEmptyTrainer
                      : l10n.messagesEmptyClient,
                  onRefresh: () => ref.refresh(provider.future),
                );
              }
              final shown = [
                for (final conversation in conversations)
                  if (switch (_filter) {
                    _Filter.all => true,
                    _Filter.private =>
                      conversation.kind == ConversationKind.direct,
                    _Filter.group =>
                      conversation.kind != ConversationKind.direct,
                  })
                    conversation,
              ];
              final now = DateTime.now();
              return RefreshIndicator(
                onRefresh: () => ref.refresh(provider.future),
                child: ListView(
                  padding: const EdgeInsets.only(bottom: 96),
                  children: [
                    _Filters(
                      selected: _filter,
                      onSelected: (filter) => setState(() => _filter = filter),
                    ),
                    if (shown.isEmpty)
                      Padding(
                        padding: const EdgeInsets.all(32),
                        child: Text(
                          l10n.messagesEmptyFilter,
                          textAlign: TextAlign.center,
                        ),
                      ),
                    for (final conversation in shown)
                      _ConversationTile(
                        conversation,
                        now: now,
                        onTap: () async {
                          await context.push(
                            conversationLocation(
                              session.activeRole,
                              conversation.id,
                            ),
                          );
                          if (mounted) {
                            ref.invalidate(provider);
                          }
                        },
                      ),
                  ],
                ),
              );
            },
          ),
    );
  }

  /// Opens a picker or form that pops with a conversation id, then opens that
  /// conversation.
  Future<void> _startConversation(
    BuildContext context,
    Session session,
    String location,
  ) async {
    final conversationId = await context.push<String>(location);
    if (conversationId == null || !context.mounted) {
      return;
    }
    ref.invalidate(conversationsProvider(session.accountId));
    await context.push(
      conversationLocation(session.activeRole, conversationId),
    );
  }

  Future<void> _chooseNewMessage(BuildContext context, Session session) async {
    final l10n = AppLocalizations.of(context);
    final location = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
                child: Text(
                  l10n.messagesNew,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              _NewMessageOption(
                icon: Icons.person,
                title: l10n.messagesNewToTrainee,
                subtitle: l10n.messagesNewToTraineeHint,
                location: traineePickerLocation,
              ),
              _NewMessageOption(
                icon: Icons.groups,
                title: l10n.messagesNewGroupChat,
                subtitle: l10n.messagesNewGroupChatHint,
                location: newGroupChatLocation,
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
    if (location != null && context.mounted) {
      await _startConversation(context, session, location);
    }
  }
}

class _NewMessageOption extends StatelessWidget {
  const _NewMessageOption({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.location,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String location;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 4),
      leading: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: scheme.primaryContainer,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(icon, color: scheme.onPrimaryContainer),
      ),
      title: Text(title),
      subtitle: Text(subtitle),
      onTap: () => Navigator.of(context).pop(location),
    );
  }
}

class _Filters extends StatelessWidget {
  const _Filters({required this.selected, required this.onSelected});

  final _Filter selected;
  final ValueChanged<_Filter> onSelected;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final labels = {
      _Filter.all: l10n.messagesFilterAll,
      _Filter.private: l10n.messagesFilterPrivate,
      _Filter.group: l10n.messagesFilterGroup,
    };
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Wrap(
        spacing: 8,
        children: [
          for (final MapEntry(key: filter, value: label) in labels.entries)
            FilterChip(
              label: Text(label),
              selected: filter == selected,
              onSelected: (_) => onSelected(filter),
            ),
        ],
      ),
    );
  }
}

class _ConversationTile extends StatelessWidget {
  const _ConversationTile(
    this.conversation, {
    required this.now,
    required this.onTap,
  });

  final ConversationSummary conversation;
  final DateTime now;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final unread = conversation.unreadCount > 0;
    final dim = conversation.hasLeft || conversation.isClosed;
    final last = conversation.lastMessage;
    final time = last?.createdAt;
    final titleColor = dim ? scheme.outline : scheme.onSurface;
    final previewColor = dim
        ? scheme.outline
        : (unread ? scheme.onSurface : scheme.onSurfaceVariant);

    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          spacing: 14,
          children: [
            ConversationAvatar(
              kind: conversation.kind,
              title: conversation.title,
              counterpart: conversation.counterpart,
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 3,
                children: [
                  Text(
                    _title(conversation),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleMedium?.copyWith(
                      color: titleColor,
                      fontWeight: unread ? FontWeight.w700 : FontWeight.w400,
                    ),
                  ),
                  Text(
                    _preview(conversation, l10n),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: previewColor,
                      fontWeight: unread ? FontWeight.w500 : FontWeight.w400,
                      fontStyle: last?.isDeleted == true
                          ? FontStyle.italic
                          : null,
                    ),
                  ),
                ],
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              spacing: 6,
              children: [
                if (time != null)
                  Text(
                    formatListTime(time, l10n, now),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: unread ? scheme.primary : scheme.onSurfaceVariant,
                      fontWeight: unread ? FontWeight.w600 : null,
                    ),
                  ),
                if (unread)
                  Badge.count(
                    count: conversation.unreadCount,
                    backgroundColor: scheme.primary,
                    textColor: scheme.onPrimary,
                  )
                else if (dim)
                  Icon(
                    conversation.hasLeft ? Icons.logout : Icons.lock,
                    size: 16,
                    color: scheme.outline,
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  static String _title(ConversationSummary conversation) {
    final startsAt = conversation.eventStartsAt;
    if (conversation.kind == ConversationKind.event && startsAt != null) {
      return '${conversation.title} · ${formatClassStart(startsAt)}';
    }
    return conversation.title;
  }

  static String _preview(
    ConversationSummary conversation,
    AppLocalizations l10n,
  ) {
    if (conversation.hasLeft) {
      return l10n.messagesLeftPreview;
    }
    final last = conversation.lastMessage;
    if (last == null) {
      return conversation.isClosed
          ? l10n.messagesClosedPreview
          : l10n.messagesNoMessagesYet;
    }
    final text = last.isDeleted ? l10n.messagesDeleted : last.body ?? '';
    if (last.isMine) {
      return l10n.messagesYou(text);
    }
    final sender = last.senderName;
    return conversation.kind != ConversationKind.direct && sender != null
        ? l10n.messagesFrom(sender, text)
        : text;
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.text, required this.onRefresh});

  final String text;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(40, 120, 40, 96),
        children: [
          Icon(Icons.forum, size: 64, color: theme.colorScheme.outlineVariant),
          const SizedBox(height: 12),
          Text(
            l10n.messagesEmptyTitle,
            textAlign: TextAlign.center,
            style: theme.textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          Text(text, textAlign: TextAlign.center),
        ],
      ),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            const Icon(Icons.error_outline),
            Text(message, textAlign: TextAlign.center),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: Text(AppLocalizations.of(context).messagesRetry),
            ),
          ],
        ),
      ),
    );
  }
}
