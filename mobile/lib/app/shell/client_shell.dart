import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/app/shell/role_shell.dart';
import 'package:mobile/features/communication/presentation/notifications_screen.dart';
import 'package:mobile/app/theme/client/client_theme_config.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// The client app's tabs with the redesigned navigation bar (#281).
class ClientShell extends ConsumerWidget {
  const ClientShell({
    super.key,
    required this.navigationShell,
    required this.destinations,
  });

  final StatefulNavigationShell navigationShell;
  final List<ShellDestination> destinations;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      body: navigationShell,
      bottomNavigationBar: AppNavBar(
        currentIndex: navigationShell.currentIndex,
        onTap: (i) => navigationShell.goBranch(
          i,
          initialLocation: i == navigationShell.currentIndex,
        ),
        items: [
          for (final destination in destinations) _item(ref, l10n, destination),
        ],
      ),
    );
  }

  static AppNavItem _item(
    WidgetRef ref,
    AppLocalizations l10n,
    ShellDestination destination,
  ) {
    final count = destination.badge?.call(ref) ?? 0;
    final label = destination.label(l10n);
    return AppNavItem(
      icon: destination.icon,
      label: label,
      badge: count,
      semanticLabel: count > 0
          ? '$label, ${l10n.notificationsUnread(count)}'
          : null,
    );
  }
}

/// The header of a client tab: its title, the tab's own [actions] and the
/// bell with the count of unread notifications. Use it as a scaffold's
/// `appBar`.
class ClientTabHeader extends ConsumerWidget implements PreferredSizeWidget {
  const ClientTabHeader({
    super.key,
    required this.title,
    this.actions = const [],
  });

  final String title;
  final List<Widget> actions;

  @override
  Size get preferredSize =>
      Size.fromHeight(clientThemeConfig.sizes.headerHeight);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final unread = watchUnreadNotifications(ref);
    return PageHeader(
      title: title,
      actions: [
        ...actions,
        HeaderIconButton(
          icon: Icons.notifications_none,
          tooltip: l10n.notificationsOpen,
          semanticLabel: unread > 0
              ? '${l10n.notificationsOpen}, ${l10n.notificationsUnread(unread)}'
              : null,
          badge: unread,
          onPressed: () => context.push(clientNotificationsPath),
        ),
      ],
    );
  }
}
