import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/app/shell/client_shell.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/features/communication/presentation/notifications_screen.dart';
import 'package:mobile/features/communication/presentation/widgets/chat_avatar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

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

/// Unread messages, for the client messages tab badge; notifications have
/// their own bell.
int watchUnreadMessages(WidgetRef ref) {
  final auth = ref.watch(authControllerProvider);
  if (auth is! Authenticated) return 0;
  return ref.watch(unreadMessagesProvider(auth.session.accountId)).value ?? 0;
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
    if (!isTrainer) return _clientList(context, session);
    final unreadNotifications = watchUnreadNotifications(ref);

    return Scaffold(
      appBar: isTrainer
          ? AppBar(
              title: Text(l10n.navMessages),
              actions: [
                IconButton(
                  tooltip: l10n.notificationsOpen,
                  icon: Badge(
                    isLabelVisible: unreadNotifications > 0,
                    label: Text('$unreadNotifications'),
                    child: const Icon(Icons.notifications_outlined),
                  ),
                  onPressed: () => context.push(trainerNotificationsPath),
                ),
              ],
            )
          : ClientTabHeader(title: l10n.navMessages),
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
              final shown = conversations.where(_shown).toList();
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
                        onTap: () => _open(session, conversation),
                      ),
                  ],
                ),
              );
            },
          ),
    );
  }

  bool _shown(ConversationSummary conversation) => switch (_filter) {
    _Filter.all => true,
    _Filter.private => conversation.kind == ConversationKind.direct,
    _Filter.group => conversation.kind != ConversationKind.direct,
  };

  Future<void> _open(Session session, ConversationSummary conversation) async {
    await context.push(
      conversationLocation(session.activeRole, conversation.id),
    );
    if (mounted) ref.invalidate(conversationsProvider(session.accountId));
  }

  /// The client's list (#281): tabs instead of chips, square avatars, and
  /// "Napisz do trenera" pinned at the bottom.
  Widget _clientList(BuildContext context, Session session) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final provider = conversationsProvider(session.accountId);
    final filters = _Filter.values;
    return Scaffold(
      appBar: ClientTabHeader(title: l10n.navMessages),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: EdgeInsets.fromLTRB(sp.page, sp.s, sp.page, sp.s),
          child: Row(
            children: [
              const Spacer(),
              Expanded(
                child: AppButton(
                  label: l10n.messagesWriteToTrainer,
                  icon: Icons.edit_outlined,
                  expand: true,
                  onPressed: () => _startConversation(
                    context,
                    session,
                    trainerPickerLocation,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AppTabs(
            labels: [
              l10n.messagesFilterAll,
              l10n.messagesFilterPrivate,
              l10n.messagesFilterGroup,
            ],
            selected: filters.indexOf(_filter),
            onChanged: (i) => setState(() => _filter = filters[i]),
          ),
          Expanded(
            child: ref
                .watch(provider)
                .when(
                  skipLoadingOnRefresh: true,
                  skipLoadingOnReload: true,
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (error, _) => _ClientNote(
                    text: messagesErrorMessage(error, l10n, loading: true),
                    actionLabel: l10n.messagesRetry,
                    onAction: () => ref.invalidate(provider),
                  ),
                  data: (conversations) {
                    final shown = conversations.where(_shown).toList();
                    final now = DateTime.now();
                    return RefreshIndicator(
                      onRefresh: () => ref.refresh(provider.future),
                      child: shown.isEmpty
                          ? _ClientNote(
                              text: conversations.isEmpty
                                  ? l10n.messagesEmptyClient
                                  : l10n.messagesEmptyFilter,
                              scrollable: true,
                            )
                          : ListView(
                              padding: EdgeInsets.only(bottom: sp.xl),
                              children: [
                                for (final conversation in shown)
                                  _ClientConversationRow(
                                    conversation,
                                    now: now,
                                    onTap: () => _open(session, conversation),
                                  ),
                              ],
                            ),
                    );
                  },
                ),
          ),
        ],
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

  static String _title(ConversationSummary conversation) =>
      conversationListTitle(conversation);

  static String _preview(
    ConversationSummary conversation,
    AppLocalizations l10n,
  ) => conversationPreview(conversation, l10n);
}

/// The title of a conversation in a list; a class chat adds its start.
String conversationListTitle(ConversationSummary conversation) {
  final startsAt = conversation.eventStartsAt;
  if (conversation.kind == ConversationKind.event && startsAt != null) {
    return '${conversation.title} · ${formatClassStart(startsAt)}';
  }
  return conversation.title;
}

/// The last message of a conversation in a list, or why there is none.
String conversationPreview(
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

/// A conversation in the client's list.
class _ClientConversationRow extends StatelessWidget {
  const _ClientConversationRow(
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
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final unread = conversation.unreadCount > 0;
    final dim = conversation.hasLeft || conversation.isClosed;
    final last = conversation.lastMessage;
    final time = last?.createdAt;
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: EdgeInsets.symmetric(horizontal: sp.page, vertical: sp.l),
        decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClientConversationAvatar(conversation),
            SizedBox(width: sp.m + sp.xxs),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    conversationListTitle(conversation),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: ct.text.rowTitle.copyWith(
                      color: dim ? c.muted : c.ink,
                    ),
                  ),
                  SizedBox(height: sp.xxs),
                  Text(
                    conversationPreview(conversation, l10n),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: ct.text.meta.copyWith(
                      color: unread && !dim ? c.ink : c.muted,
                      fontWeight: unread && !dim ? FontWeight.w500 : null,
                      fontStyle: last?.isDeleted == true
                          ? FontStyle.italic
                          : null,
                    ),
                  ),
                ],
              ),
            ),
            SizedBox(width: sp.m),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (time != null)
                  Text(
                    formatListTime(time, l10n, now),
                    style: ct.text.caption.copyWith(color: c.muted),
                  ),
                SizedBox(height: sp.s),
                if (unread)
                  CountBadge(count: conversation.unreadCount)
                else if (dim)
                  Icon(
                    conversation.hasLeft ? Icons.logout : Icons.lock_outline,
                    size: ct.sizes.badge,
                    color: c.muted,
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// A short note in place of the client's list, with an optional action.
class _ClientNote extends StatelessWidget {
  const _ClientNote({
    required this.text,
    this.actionLabel,
    this.onAction,
    this.scrollable = false,
  });

  final String text;
  final String? actionLabel;
  final VoidCallback? onAction;

  /// Keeps pull to refresh working on an empty list.
  final bool scrollable;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return ListView(
      physics: scrollable ? null : const NeverScrollableScrollPhysics(),
      padding: EdgeInsets.fromLTRB(sp.page, sp.xl, sp.page, sp.xl),
      children: [
        Text(text, style: ct.text.body.copyWith(color: ct.colors.muted)),
        if (actionLabel != null) ...[
          SizedBox(height: sp.l),
          Align(
            alignment: Alignment.centerLeft,
            child: AppButton(
              label: actionLabel!,
              small: true,
              onPressed: onAction,
            ),
          ),
        ],
      ],
    );
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
