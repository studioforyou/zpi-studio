import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/push/push_device.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/core/push/push_messaging.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/features/communication/data/open_conversation.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The notification type of a new message in a conversation.
const newMessageType = 'NEW_MESSAGE';

/// Shows push messages that arrive while the app is open.
final notificationMessengerKey = GlobalKey<ScaffoldMessengerState>();

/// Connects push notifications with the app: registers the device when an
/// account signs in, opens the related screen when a notification is tapped
/// and refreshes the inbox when one arrives.
class PushNotifications {
  PushNotifications(this._ref);

  final Ref _ref;
  final _subscriptions = <StreamSubscription<Object?>>[];
  String? _accountId;
  Map<String, String>? _pending;

  void _start() {
    final messaging = _ref.read(pushMessagingProvider);
    _subscriptions
      ..add(messaging.onOpened.listen((message) => open(message.data)))
      ..add(messaging.onForegroundMessage.listen(_showInApp));
    unawaited(
      messaging
          .initialMessage()
          .then((message) {
            if (message != null) open(message.data);
          })
          .catchError((Object _) {}),
    );
  }

  void _onAuth(AuthState state) {
    switch (state) {
      case Authenticated(:final session, :final choosingRole):
        if (_accountId != session.accountId) {
          _accountId = session.accountId;
          unawaited(_ref.read(pushDeviceProvider).register());
        }
        final pending = _pending;
        if (!choosingRole && pending != null) {
          _pending = null;
          open(pending);
        }
      case Unauthenticated():
        _accountId = null;
      case AuthRestoring():
        break;
    }
  }

  /// Opens what a notification is about (its `data`) and marks it read. A
  /// notification tapped before the session is ready opens once it is.
  void open(Map<String, String> data) {
    final auth = _ref.read(authControllerProvider);
    if (auth is! Authenticated || auth.choosingRole) {
      _pending = data;
      return;
    }
    final notificationId = data['notificationId'];
    if (notificationId != null) unawaited(_markRead(notificationId));

    final session = auth.session;
    final router = _ref.read(appRouterProvider);
    final conversationId = data['conversationId'];
    if (data['type'] == newMessageType && conversationId != null) {
      _openConversation(session, conversationId, data['role']);
      return;
    }
    if (data['type'] == 'CLASS_REMINDER' &&
        session.roles.contains(AppRole.client)) {
      // A reminder about a child opens the child's reservations.
      final choice = session.choices
          .where(
            (choice) =>
                choice.role == AppRole.client &&
                choice.profile?.id == data['profileId'],
          )
          .firstOrNull;
      if (choice != null &&
          (session.activeRole != AppRole.client ||
              session.activeProfileId != choice.profile?.id)) {
        _ref.read(authControllerProvider.notifier).choose(choice);
      }
      router.go(clientReservationsPath);
      return;
    }
    router.go(
      session.activeRole == AppRole.client
          ? clientNotificationsPath
          : trainerNotificationsPath,
    );
  }

  /// Opens a conversation in the role the message concerns, above that
  /// role's messages tab.
  void _openConversation(
    Session session,
    String conversationId,
    String? roleCode,
  ) {
    var role = session.activeRole;
    final concerned = AppRole.fromApi(roleCode ?? '');
    if (concerned != null &&
        concerned != role &&
        session.roles.contains(concerned)) {
      // Conversations belong to the account: a client reads them in their own
      // profile rather than a child's.
      final choices = session.choices.where((c) => c.role == concerned);
      final choice =
          choices.where((c) => !(c.profile?.isChild ?? false)).firstOrNull ??
          choices.firstOrNull;
      if (choice != null) {
        _ref.read(authControllerProvider.notifier).choose(choice);
        role = concerned;
      }
    }
    final open = _ref.read(openConversationProvider);
    if (open.id == conversationId && role == session.activeRole) {
      open.messageArrived(conversationId);
      return;
    }
    final router = _ref.read(appRouterProvider)
      ..go(role == AppRole.client ? clientMessagesPath : trainerMessagesPath);
    unawaited(router.push(conversationLocation(role, conversationId)));
  }

  /// Reloads the inbox and the unread badge.
  void refresh() {
    _ref
      ..invalidate(inboxProvider)
      ..invalidate(unreadCountProvider);
  }

  Future<void> _markRead(String notificationId) async {
    try {
      await _ref.read(notificationsApiProvider).markRead(notificationId);
    } catch (_) {
      // Stays unread; the inbox can mark it later.
    }
    refresh();
  }

  void _showInApp(PushMessage message) {
    refresh();
    final conversationId = message.data['conversationId'];
    if (message.data['type'] == newMessageType && conversationId != null) {
      _ref
        ..invalidate(conversationsProvider)
        ..invalidate(unreadMessagesProvider);
      // The conversation on screen shows the message itself.
      final open = _ref.read(openConversationProvider);
      if (open.id == conversationId) {
        open.messageArrived(conversationId);
        final notificationId = message.data['notificationId'];
        if (notificationId != null) unawaited(_markRead(notificationId));
        return;
      }
    }
    final messenger = notificationMessengerKey.currentState;
    final text = [
      message.title,
      message.body,
    ].whereType<String>().where((part) => part.isNotEmpty).join('\n');
    if (messenger == null || text.isEmpty) return;
    messenger.showSnackBar(
      SnackBar(
        content: Text(text),
        action: SnackBarAction(
          label: AppLocalizations.of(messenger.context).notificationsShow,
          onPressed: () => open(message.data),
        ),
      ),
    );
  }

  void _dispose() {
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
  }
}

final pushNotificationsProvider = Provider<PushNotifications>((ref) {
  final notifications = PushNotifications(ref).._start();
  ref.listen(
    authControllerProvider,
    (_, state) => notifications._onAuth(state),
    fireImmediately: true,
  );
  ref.onDispose(notifications._dispose);
  return notifications;
});
