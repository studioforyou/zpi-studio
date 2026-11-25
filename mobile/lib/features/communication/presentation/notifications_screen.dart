import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/push/push_notifications.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// The number of unread notifications for the active role, for the tab badge.
int watchUnreadNotifications(WidgetRef ref) {
  final auth = ref.watch(authControllerProvider);
  if (auth is! Authenticated) return 0;
  final session = auth.session;
  final scope = (
    accountId: session.accountId,
    role: session.activeRole.apiValue,
  );
  return ref.watch(unreadCountProvider(scope)).value?.count ?? 0;
}

/// Unread notifications that are not about messages, which the messages tab
/// badge adds to the unread messages.
int watchUnreadOtherNotifications(WidgetRef ref) {
  final auth = ref.watch(authControllerProvider);
  if (auth is! Authenticated) return 0;
  final session = auth.session;
  final scope = (
    accountId: session.accountId,
    role: session.activeRole.apiValue,
  );
  return ref.watch(unreadCountProvider(scope)).value?.others ?? 0;
}

/// The notification inbox for the active role, opened with the bell on the
/// messages tab. Mark all as read is in the top right corner.
class NotificationsScreen extends ConsumerWidget {
  const NotificationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final scope = session == null
        ? null
        : (accountId: session.accountId, role: session.activeRole.apiValue);
    final unread = scope == null
        ? 0
        : ref.watch(unreadCountProvider(scope)).value?.count ?? 0;
    if (session?.activeRole == AppRole.client && scope != null) {
      return _ClientNotifications(
        scope: scope,
        unread: unread,
        onMarkAllRead: () => _markAllRead(context, ref, scope),
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: Text(
          l10n.notificationsTitle,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          IconButton(
            tooltip: l10n.notificationsAbout,
            icon: const Icon(Icons.info_outline),
            onPressed: () => showDialog<void>(
              context: context,
              builder: (context) => AlertDialog(
                title: Text(l10n.notificationsTitle),
                content: Text(l10n.notificationsAboutBody),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: Text(
                      MaterialLocalizations.of(context).closeButtonLabel,
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (scope != null)
            IconButton(
              tooltip: l10n.notificationsMarkAllRead,
              icon: const Icon(Icons.done_all),
              onPressed: unread > 0
                  ? () => _markAllRead(context, ref, scope)
                  : null,
            ),
        ],
      ),
      body: scope == null
          ? const SizedBox.shrink()
          : _Inbox(key: ValueKey(scope), scope: scope),
    );
  }

  Future<void> _markAllRead(
    BuildContext context,
    WidgetRef ref,
    InboxScope scope,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final error = AppLocalizations.of(context).notificationsMarkError;
    try {
      await ref.read(notificationsApiProvider).markAllRead(scope.role);
    } catch (_) {
      messenger.showSnackBar(SnackBar(content: Text(error)));
    }
    ref.read(pushNotificationsProvider).refresh();
  }
}

class _Inbox extends ConsumerStatefulWidget {
  const _Inbox({super.key, required this.scope, this.client = false});

  final InboxScope scope;

  /// The client's look (#281): sections and flat rows.
  final bool client;

  @override
  ConsumerState<_Inbox> createState() => _InboxState();
}

class _InboxState extends ConsumerState<_Inbox> with WidgetsBindingObserver {
  int _page = 0;

  InboxQuery get _query => (scope: widget.scope, page: _page);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_refresh());
  }

  Future<void> _refresh() async {
    setState(() => _page = 0);
    ref.read(pushNotificationsProvider).refresh();
    try {
      await ref.read(inboxProvider((scope: widget.scope, page: 0)).future);
    } catch (_) {
      // The provider renders the error and keeps retry available.
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.client) return _buildClient(context);
    final l10n = AppLocalizations.of(context);
    return ref
        .watch(inboxProvider(_query))
        .when(
          skipLoadingOnRefresh: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                spacing: 16,
                children: [
                  Text(
                    l10n.notificationsLoadError,
                    textAlign: TextAlign.center,
                  ),
                  OutlinedButton(
                    onPressed: _refresh,
                    child: Text(l10n.notificationsRetry),
                  ),
                ],
              ),
            ),
          ),
          data: (result) => RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.symmetric(vertical: 8),
              children: [
                if (result.items.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(32),
                    child: Text(
                      l10n.notificationsEmpty,
                      textAlign: TextAlign.center,
                    ),
                  ),
                for (final notification in result.items)
                  _NotificationTile(notification: notification),
                if (result.totalPages > 1 || _page > 0)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        TextButton(
                          onPressed: _page > 0
                              ? () => setState(() => _page--)
                              : null,
                          child: Text(l10n.notificationsNewer),
                        ),
                        Text(
                          l10n.notificationsPage(
                            result.page + 1,
                            result.totalPages,
                          ),
                        ),
                        TextButton(
                          onPressed: _page + 1 < result.totalPages
                              ? () => setState(() => _page++)
                              : null,
                          child: Text(l10n.notificationsOlder),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        );
  }

  Widget _buildClient(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    return ref
        .watch(inboxProvider(_query))
        .when(
          skipLoadingOnRefresh: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => ListView(
            padding: EdgeInsets.all(sp.page),
            children: [
              Text(l10n.notificationsLoadError, style: ct.text.body),
              SizedBox(height: sp.l),
              Align(
                alignment: Alignment.centerLeft,
                child: AppButton(
                  label: l10n.notificationsRetry,
                  small: true,
                  onPressed: _refresh,
                ),
              ),
            ],
          ),
          data: (result) {
            final fresh = result.items.where((n) => n.isUnread).toList();
            final earlier = result.items.where((n) => !n.isUnread).toList();
            final now = DateTime.now();
            return RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: EdgeInsets.only(bottom: sp.xl),
                children: [
                  if (result.items.isEmpty)
                    Padding(
                      padding: EdgeInsets.all(sp.page),
                      child: Text(
                        l10n.notificationsEmpty,
                        style: ct.text.body.copyWith(color: ct.colors.muted),
                      ),
                    ),
                  for (final (label, items) in [
                    (l10n.notificationsSectionNew, fresh),
                    (l10n.notificationsSectionEarlier, earlier),
                  ])
                    if (items.isNotEmpty) ...[
                      SectionLabel(label),
                      DecoratedBox(
                        decoration: BoxDecoration(
                          border: Border(top: ct.hairline),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            for (final notification in items)
                              _ClientNotificationRow(
                                notification: notification,
                                now: now,
                              ),
                          ],
                        ),
                      ),
                    ],
                  if (result.totalPages > 1 || _page > 0)
                    Padding(
                      padding: EdgeInsets.symmetric(horizontal: sp.page),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          AppButton.ghost(
                            label: l10n.notificationsNewer,
                            onPressed: _page > 0
                                ? () => setState(() => _page--)
                                : null,
                          ),
                          Text(
                            l10n.notificationsPage(
                              result.page + 1,
                              result.totalPages,
                            ),
                            style: ct.text.meta.copyWith(
                              color: ct.colors.muted,
                            ),
                          ),
                          AppButton.ghost(
                            label: l10n.notificationsOlder,
                            onPressed: _page + 1 < result.totalPages
                                ? () => setState(() => _page++)
                                : null,
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            );
          },
        );
  }
}

class _NotificationTile extends ConsumerWidget {
  const _NotificationTile({required this.notification});

  final AppNotification notification;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final unread = notification.isUnread;
    final l10n = AppLocalizations.of(context);
    final substitution = notification.type == 'SUBSTITUTION_AVAILABLE';
    final name = substitution
        ? notification.data['title']
        : notification.data['eventName'];
    final startsAt = DateTime.tryParse(
      (substitution
              ? notification.data['startsAt']
              : notification.data['eventStartsAt']) ??
          '',
    )?.toLocal();
    final deadline = DateTime.tryParse(
      notification.data['offerExpiresAt'] ?? '',
    )?.toLocal();
    final structured =
        (_isWaitlist || substitution) && name != null && startsAt != null;
    final icon = switch (notification.type) {
      'CLASS_REMINDER' => Icons.event_outlined,
      'CLASS_CHANGED' => Icons.edit_calendar_outlined,
      'CLASS_CANCELLED' || 'CLASS_AUTO_CANCELLED' => Icons.event_busy_outlined,
      'NEW_MESSAGE' => Icons.chat_bubble_outline,
      'SUBSTITUTION_AVAILABLE' => Icons.swap_horiz,
      'PASS_EXPIRING' ||
      'PASS_LAST_ENTRY_USED' => Icons.confirmation_number_outlined,
      'WAITLIST_PLACE_OFFERED' => Icons.event_seat_outlined,
      'WAITLIST_PLACE_GRANTED' => Icons.event_available_outlined,
      'TIME_OFF_APPROVED' ||
      'TIME_OFF_REJECTED' ||
      'TIME_OFF_CANCELLED' => Icons.event_busy_outlined,
      _ => Icons.notifications_outlined,
    };
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      elevation: 0,
      color: unread
          ? theme.colorScheme.primaryContainer.withValues(alpha: .28)
          : theme.colorScheme.surfaceContainerLow,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => ref.read(pushNotificationsProvider).open(_data),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 8, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: CircleAvatar(
                  radius: 16,
                  backgroundColor: theme.colorScheme.primaryContainer,
                  foregroundColor: theme.colorScheme.onPrimaryContainer,
                  child: Icon(icon, size: 18),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 5,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            notification.title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelLarge?.copyWith(
                              fontWeight: unread
                                  ? FontWeight.w700
                                  : FontWeight.w500,
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                        if (unread) ...[
                          const SizedBox(width: 6),
                          Icon(
                            Icons.circle,
                            size: 6,
                            color: theme.colorScheme.primary,
                          ),
                        ],
                        SizedBox(
                          width: 32,
                          height: 32,
                          child: IconButton(
                            padding: EdgeInsets.zero,
                            tooltip: l10n.notificationsFullText,
                            icon: Icon(
                              Icons.info_outline,
                              size: 18,
                              color: theme.colorScheme.outline,
                            ),
                            onPressed: () => _showFullText(context, ref),
                          ),
                        ),
                      ],
                    ),
                    if (structured) ...[
                      Text(
                        name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleSmall,
                      ),
                      Wrap(
                        spacing: 8,
                        runSpacing: 6,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(
                            DateFormat(
                              'EEE, d.MM · HH:mm',
                              'pl',
                            ).format(startsAt),
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                          if (_isWaitlist && deadline != null)
                            _deadline(context, deadline),
                          if (substitution &&
                              notification.data['rooms'] != null)
                            Text(
                              notification.data['rooms']!,
                              style: theme.textTheme.bodySmall,
                            ),
                        ],
                      ),
                    ] else
                      Text(
                        notification.body,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium,
                      ),
                    Text(
                      _when(l10n, notification.createdAt),
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.outline,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  bool get _isWaitlist =>
      notification.type == 'WAITLIST_PLACE_OFFERED' ||
      notification.type == 'WAITLIST_PLACE_GRANTED';

  Map<String, String> get _data => {
    ...notification.data,
    'notificationId': notification.id,
    'type': notification.type,
  };

  Widget _deadline(BuildContext context, DateTime at) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    final now = DateTime.now();
    final upcoming = at.isAfter(now);
    final sameDay =
        at.year == now.year && at.month == now.month && at.day == now.day;
    final time = DateFormat(sameDay ? 'HH:mm' : 'd.MM, HH:mm').format(at);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: upcoming
            ? theme.colorScheme.primaryContainer
            : theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        upcoming
            ? l10n.notificationsConfirmBy(time)
            : l10n.notificationsDeadlineAt(time),
        style: theme.textTheme.labelSmall?.copyWith(
          color: upcoming
              ? theme.colorScheme.onPrimaryContainer
              : theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }

  void _showFullText(BuildContext context, WidgetRef ref) {
    final push = ref.read(pushNotificationsProvider);
    final l10n = AppLocalizations.of(context);
    if (notification.isUnread) unawaited(push.markRead(notification.id));
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(sheetContext).height * .75,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              spacing: 16,
              children: [
                Text(
                  notification.title,
                  style: Theme.of(sheetContext).textTheme.titleMedium,
                ),
                Text(notification.body),
                Text(
                  _when(l10n, notification.createdAt),
                  style: Theme.of(sheetContext).textTheme.bodySmall,
                ),
                if (_isWaitlist)
                  FilledButton.icon(
                    onPressed: () {
                      Navigator.of(sheetContext).pop();
                      push.open(_data);
                    },
                    icon: const Icon(Icons.arrow_forward, size: 18),
                    label: Text(l10n.notificationsOpenClass),
                  )
                else
                  TextButton(
                    onPressed: () => Navigator.of(sheetContext).pop(),
                    child: Text(
                      MaterialLocalizations.of(sheetContext).closeButtonLabel,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  static String _when(AppLocalizations l10n, DateTime at) {
    final now = DateTime.now();
    final time = DateFormat('HH:mm').format(at);
    final day = DateTime(at.year, at.month, at.day);
    final today = DateTime(now.year, now.month, now.day);
    if (day == today) return l10n.notificationsToday(time);
    if (day == today.subtract(const Duration(days: 1))) {
      return l10n.notificationsYesterday(time);
    }
    return DateFormat('d.MM.yyyy, HH:mm').format(at);
  }
}

/// The client's inbox (#281): "‹ Wstecz" with "Oznacz jako przeczytane",
/// the title, then new and earlier notifications.
class _ClientNotifications extends StatelessWidget {
  const _ClientNotifications({
    required this.scope,
    required this.unread,
    required this.onMarkAllRead,
  });

  final InboxScope scope;
  final int unread;
  final VoidCallback onMarkAllRead;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Scaffold(
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SubPageHeader(
            backLabel: l10n.commonBack,
            title: l10n.notificationsTitle,
            backTrailing: Semantics(
              button: true,
              enabled: unread > 0,
              label: l10n.notificationsMarkAllRead,
              excludeSemantics: true,
              child: InkWell(
                onTap: unread > 0 ? onMarkAllRead : null,
                child: Container(
                  constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
                  padding: EdgeInsets.symmetric(horizontal: sp.s),
                  alignment: Alignment.centerRight,
                  child: Text(
                    l10n.notificationsMarkAllReadShort,
                    style: ct.text.meta.copyWith(
                      color: unread > 0 ? c.ink : c.faint,
                    ),
                  ),
                ),
              ),
            ),
            actions: [
              HeaderIconButton(
                icon: Icons.info_outline,
                tooltip: l10n.notificationsAbout,
                onPressed: () => showAppSheet<void>(
                  context,
                  builder: (sheetContext) => AppSheet(
                    title: l10n.notificationsAbout,
                    titleSmall: true,
                    children: [
                      SizedBox(height: sp.m),
                      Text(l10n.notificationsAboutBody, style: ct.text.body),
                    ],
                  ),
                ),
              ),
            ],
          ),
          Expanded(
            child: _Inbox(key: ValueKey(scope), scope: scope, client: true),
          ),
        ],
      ),
    );
  }
}

/// One notification in the client's inbox: what it is about, the title with
/// an unread mark, two lines of text, a deadline to confirm a place and the
/// time. Tapping opens what it is about, or the full text when there is
/// nothing to open; a long press always shows the full text.
class _ClientNotificationRow extends ConsumerWidget {
  const _ClientNotificationRow({required this.notification, required this.now});

  final AppNotification notification;
  final DateTime now;

  Map<String, String> get _data => {
    ...notification.data,
    'notificationId': notification.id,
    'type': notification.type,
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final type = notification.type;
    final (kicker, attention) = switch (type) {
      'WAITLIST_PLACE_OFFERED' => (l10n.notificationsKindOffer, true),
      'WAITLIST_PLACE_GRANTED' => (l10n.notificationsKindGranted, true),
      'CLASS_CHANGED' => (l10n.notificationsKindChanged, true),
      'CLASS_CANCELLED' => (l10n.notificationsKindCancelled, true),
      'CLASS_REMINDER' => (l10n.notificationsKindReminder, false),
      'PASS_EXPIRING' ||
      'PASS_LAST_ENTRY_USED' => (l10n.notificationsKindPass, false),
      'NEW_MESSAGE' => (l10n.notificationsKindMessage, false),
      _ => (l10n.notificationsKindStudio, false),
    };
    final deadline = type == 'WAITLIST_PLACE_OFFERED'
        ? DateTime.tryParse(
            notification.data['offerExpiresAt'] ?? '',
          )?.toLocal()
        : null;
    final opens = clientNotificationOpensScreen(_data);
    final push = ref.read(pushNotificationsProvider);
    return InkWell(
      onTap: opens
          ? () => push.open(_data)
          : () => _showClientFullText(context, ref, notification, now),
      onLongPress: () => _showClientFullText(context, ref, notification, now),
      child: Container(
        padding: EdgeInsets.fromLTRB(sp.page, sp.l - sp.xxs, sp.page, sp.l),
        decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    kicker,
                    style: ct.text.kicker.copyWith(
                      color: attention ? c.orange : c.muted,
                    ),
                  ),
                  SizedBox(height: sp.xxs + 1),
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          notification.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: ct.text.rowTitle,
                        ),
                      ),
                      if (notification.isUnread) ...[
                        SizedBox(width: sp.s),
                        SizedBox.square(
                          dimension: ct.sizes.unreadDot,
                          child: ColoredBox(color: c.orange),
                        ),
                      ],
                    ],
                  ),
                  SizedBox(height: sp.xxs + 1),
                  Text(
                    notification.body,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: ct.text.meta.copyWith(color: c.muted),
                  ),
                  if (deadline != null) ...[
                    SizedBox(height: sp.s),
                    _DeadlineTag(at: deadline, now: now),
                  ],
                ],
              ),
            ),
            SizedBox(width: sp.m),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  formatListTime(notification.createdAt, l10n, now),
                  style: ct.text.caption.copyWith(color: c.muted),
                ),
                if (opens) ...[
                  SizedBox(height: sp.s),
                  Icon(
                    Icons.chevron_right,
                    size: ct.sizes.icon,
                    color: c.faint,
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// "Potwierdź do 16:54" in orange, or the past deadline in gray.
class _DeadlineTag extends StatelessWidget {
  const _DeadlineTag({required this.at, required this.now});

  final DateTime at;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final upcoming = at.isAfter(now);
    final sameDay =
        at.year == now.year && at.month == now.month && at.day == now.day;
    final time = DateFormat(sameDay ? 'HH:mm' : 'd.MM, HH:mm').format(at);
    final color = upcoming ? c.orange : c.muted;
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: ct.spacing.s,
        vertical: ct.spacing.xxs,
      ),
      decoration: BoxDecoration(
        border: Border.all(color: color, width: ct.shape.hairline),
      ),
      child: Text(
        upcoming
            ? l10n.notificationsConfirmBy(time)
            : l10n.notificationsDeadlineAt(time),
        style: ct.text.kicker.copyWith(color: color),
      ),
    );
  }
}

/// The whole notification in a sheet; reading it marks it as read.
void _showClientFullText(
  BuildContext context,
  WidgetRef ref,
  AppNotification notification,
  DateTime now,
) {
  final push = ref.read(pushNotificationsProvider);
  final l10n = AppLocalizations.of(context);
  if (notification.isUnread) unawaited(push.markRead(notification.id));
  final opens = clientNotificationOpensScreen({
    ...notification.data,
    'type': notification.type,
  });
  showAppSheet<void>(
    context,
    builder: (sheetContext) {
      final ct = sheetContext.ct;
      final sp = ct.spacing;
      return AppSheet(
        kicker: l10n.notificationsFullText,
        title: notification.title,
        titleSmall: true,
        children: [
          SizedBox(height: sp.m),
          Text(notification.body, style: ct.text.body),
          SizedBox(height: sp.m),
          Text(
            _NotificationTile._when(l10n, notification.createdAt),
            style: ct.text.meta.copyWith(color: ct.colors.muted),
          ),
          if (opens) ...[
            SizedBox(height: sp.l),
            AppButton.primary(
              label: switch (notification.type) {
                'NEW_MESSAGE' => l10n.notificationsOpenConversation,
                'PASS_EXPIRING' ||
                'PASS_LAST_ENTRY_USED' => l10n.notificationsOpenPasses,
                _ => l10n.notificationsOpenClass,
              },
              onPressed: () {
                Navigator.of(sheetContext).pop();
                push.open({
                  ...notification.data,
                  'notificationId': notification.id,
                  'type': notification.type,
                });
              },
            ),
          ],
        ],
      );
    },
  );
}
