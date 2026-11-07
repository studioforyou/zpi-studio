import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/features/communication/presentation/notifications_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The messages tab. The bell in the top right corner opens the notifications,
/// with the number of unread ones on it.
class MessagesScreen extends ConsumerWidget {
  const MessagesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final unread = watchUnreadNotifications(ref);
    final client = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session.activeRole == AppRole.client,
      _ => true,
    };
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.navMessages),
        actions: [
          IconButton(
            tooltip: l10n.notificationsOpen,
            icon: Badge(
              isLabelVisible: unread > 0,
              label: Text('$unread'),
              child: const Icon(Icons.notifications_outlined),
            ),
            onPressed: () => context.push(
              client ? clientNotificationsPath : trainerNotificationsPath,
            ),
          ),
        ],
      ),
      body: Center(child: Text(l10n.placeholderComingSoon)),
    );
  }
}
