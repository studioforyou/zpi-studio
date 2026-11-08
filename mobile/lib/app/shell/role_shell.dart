import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ShellDestination {
  const ShellDestination({required this.icon, required this.label, this.badge});

  final IconData icon;
  final String Function(AppLocalizations) label;

  /// A number shown on the tab's icon (e.g. unread notifications); hidden
  /// when 0.
  final int Function(WidgetRef)? badge;
}

class RoleShell extends ConsumerWidget {
  const RoleShell({
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
      bottomNavigationBar: NavigationBar(
        selectedIndex: navigationShell.currentIndex,
        onDestinationSelected: (i) => navigationShell.goBranch(
          i,
          initialLocation: i == navigationShell.currentIndex,
        ),
        destinations: [
          for (final destination in destinations)
            NavigationDestination(
              icon: _icon(ref, l10n, destination),
              label: destination.label(l10n),
            ),
        ],
      ),
    );
  }

  static Widget _icon(
    WidgetRef ref,
    AppLocalizations l10n,
    ShellDestination destination,
  ) {
    final icon = Icon(destination.icon);
    final count = destination.badge?.call(ref) ?? 0;
    if (count == 0) return icon;
    return Badge(
      label: Text(count > 99 ? '99+' : '$count'),
      child: Semantics(label: l10n.notificationsUnread(count), child: icon),
    );
  }
}
