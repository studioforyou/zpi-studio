import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';
import 'package:mobile/shared/widgets/client/status.dart';

/// One item of an [AppNavBar].
class AppNavItem {
  const AppNavItem({
    required this.icon,
    required this.label,
    this.badge = 0,
    this.semanticLabel,
  });

  final IconData icon;
  final String label;
  final int badge;
  final String? semanticLabel;
}

/// The client bottom navigation: equal items, a hairline on top, the
/// selected icon and label in the accent colour. No pill, no raised item.
class AppNavBar extends StatelessWidget {
  const AppNavBar({
    super.key,
    required this.items,
    required this.currentIndex,
    required this.onTap,
  });

  final List<AppNavItem> items;
  final int currentIndex;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Material(
      color: c.background,
      child: DecoratedBox(
        decoration: BoxDecoration(border: Border(top: ct.hairline)),
        child: SafeArea(
          top: false,
          child: SizedBox(
            height: ct.sizes.navBarHeight,
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: sp.s),
              child: Row(
                children: [
                  for (final (i, item) in items.indexed)
                    Expanded(
                      child: Semantics(
                        selected: i == currentIndex,
                        button: true,
                        label: item.semanticLabel,
                        child: InkWell(
                          onTap: () => onTap(i),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              WithBadge(
                                count: item.badge,
                                child: Icon(
                                  item.icon,
                                  size: ct.sizes.navIcon,
                                  color: i == currentIndex ? c.accent : c.muted,
                                ),
                              ),
                              SizedBox(height: sp.xs),
                              Text(
                                item.label,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: i == currentIndex
                                    ? ct.text.navLabel.copyWith(
                                        color: c.accent,
                                        fontWeight: ct.text.selectedWeight,
                                      )
                                    : ct.text.navLabel,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
