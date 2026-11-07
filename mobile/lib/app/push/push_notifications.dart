import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/push/push_device.dart';
import 'package:mobile/core/push/push_messaging.dart';
import 'package:mobile/features/communication/data/notifications_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

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
