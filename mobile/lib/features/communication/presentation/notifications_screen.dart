import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/push/push_notifications.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

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
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.notificationsTitle),
        actions: [
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
  const _Inbox({super.key, required this.scope});

  final InboxScope scope;

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
}

class _NotificationTile extends ConsumerWidget {
  const _NotificationTile({required this.notification});

  final AppNotification notification;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final unread = notification.isUnread;
    return ListTile(
      leading: Icon(switch (notification.type) {
        'CLASS_REMINDER' => Icons.event_outlined,
        'PASS_EXPIRING' ||
        'PASS_LAST_ENTRY_USED' => Icons.confirmation_number_outlined,
        'NEW_MESSAGE' => Icons.chat_bubble_outline,
        _ => Icons.notifications_outlined,
      }, color: theme.colorScheme.primary),
      title: Text(
        notification.title,
        style: unread ? const TextStyle(fontWeight: FontWeight.w600) : null,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 2,
        children: [
          Text(notification.body),
          Text(
            _when(AppLocalizations.of(context), notification.createdAt),
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
      trailing: unread
          ? Icon(Icons.circle, size: 10, color: theme.colorScheme.primary)
          : null,
      onTap: () => ref.read(pushNotificationsProvider).open({
        ...notification.data,
        'notificationId': notification.id,
        'type': notification.type,
      }),
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
