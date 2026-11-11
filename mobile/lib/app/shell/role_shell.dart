import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class ShellDestination {
  const ShellDestination({
    required this.icon,
    required this.label,
    this.badge,
    this.onShown,
  });

  final IconData icon;
  final String Function(AppLocalizations) label;

  /// A number shown on the tab's icon (e.g. unread notifications); hidden
  /// when 0.
  final int Function(WidgetRef)? badge;

  /// Called while the tab is the one on screen, e.g. to clear its [badge].
  final void Function(WidgetRef)? onShown;
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
    final current = destinations[navigationShell.currentIndex];
    if (current.onShown case final onShown?) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (context.mounted) onShown(ref);
      });
    }
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
              icon: _icon(
                ref,
                l10n,
                destination,
                shown: identical(destination, current),
              ),
              label: destination.label(l10n),
            ),
        ],
      ),
    );
  }

  static Widget _icon(
    WidgetRef ref,
    AppLocalizations l10n,
    ShellDestination destination, {
    required bool shown,
  }) {
    final icon = Icon(destination.icon);
    final count = destination.badge?.call(ref) ?? 0;
    // A tab that clears its number when shown does not show it meanwhile.
    if (count == 0 || (shown && destination.onShown != null)) return icon;
    return Badge(
      label: Text(count > 99 ? '99+' : '$count'),
      child: Semantics(label: l10n.notificationsUnread(count), child: icon),
    );
  }
}
