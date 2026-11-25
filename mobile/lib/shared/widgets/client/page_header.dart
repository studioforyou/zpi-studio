import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';
import 'package:mobile/shared/widgets/client/status.dart';

/// The header of a client tab: the title on the left and icon buttons on the
/// right (history, bell).
class PageHeader extends StatelessWidget {
  const PageHeader({super.key, required this.title, this.actions = const []});

  final String title;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return SafeArea(
      bottom: false,
      child: Container(
        constraints: BoxConstraints(minHeight: ct.sizes.headerHeight),
        padding: EdgeInsets.only(left: sp.page, right: sp.m, top: sp.m),
        alignment: Alignment.centerLeft,
        child: Row(
          children: [
            Expanded(
              child: Semantics(
                header: true,
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: ct.text.pageTitle,
                ),
              ),
            ),
            ...actions,
          ],
        ),
      ),
    );
  }
}

/// The header of a screen opened from a tab: a back link with the tab's
/// name above the large title ("‹ Zajęcia" / "Historia treningów").
class SubPageHeader extends StatelessWidget {
  const SubPageHeader({
    super.key,
    required this.backLabel,
    required this.title,
    this.actions = const [],
  });

  final String backLabel;
  final String title;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: EdgeInsets.only(left: sp.page, right: sp.m, top: sp.s),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(
              button: true,
              label: backLabel,
              excludeSemantics: true,
              child: InkWell(
                onTap: () => Navigator.of(context).maybePop(),
                child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.chevron_left,
                        size: ct.sizes.icon,
                        color: ct.colors.ink,
                      ),
                      SizedBox(width: sp.xxs),
                      Text(backLabel, style: ct.text.body),
                    ],
                  ),
                ),
              ),
            ),
            Row(
              children: [
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(title, style: ct.text.pageTitle),
                  ),
                ),
                ...actions,
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// An icon button for a [PageHeader], optionally with a count badge.
class HeaderIconButton extends StatelessWidget {
  const HeaderIconButton({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.badge = 0,
    this.semanticLabel,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final int badge;

  /// Spoken instead of [tooltip], for example with the unread count.
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Semantics(
      label: semanticLabel,
      button: true,
      excludeSemantics: semanticLabel != null,
      child: IconButton(
        onPressed: onPressed,
        tooltip: tooltip,
        constraints: BoxConstraints.tight(Size.square(ct.sizes.tapTarget)),
        icon: WithBadge(
          count: badge,
          child: Icon(icon, size: ct.sizes.icon, color: ct.colors.ink),
        ),
      ),
    );
  }
}
