import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';

/// A list row: title, optional subtitle and value, and a chevron when it can
/// be tapped. Rows are separated by hairlines; the last row of a list also
/// sets [bottomLine].
class AppRow extends StatelessWidget {
  const AppRow({
    super.key,
    required this.title,
    this.subtitle,
    this.value,
    this.leading,
    this.trailing,
    this.onTap,
    this.unread = false,
    this.dimmed = false,
    this.topLine = true,
    this.bottomLine = false,
    this.horizontalPadding,
  });

  final String title;
  final String? subtitle;

  /// Short text on the right, in muted.
  final String? value;
  final Widget? leading;

  /// Replaces the chevron.
  final Widget? trailing;
  final VoidCallback? onTap;

  /// Adds an orange square after the title.
  final bool unread;

  /// Writes the title in muted (past or inactive items).
  final bool dimmed;
  final bool topLine;
  final bool bottomLine;

  /// Defaults to the page padding.
  final double? horizontalPadding;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Semantics(
      button: onTap != null,
      child: InkWell(
        onTap: onTap,
        child: Container(
          constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
          padding: EdgeInsets.symmetric(
            horizontal: horizontalPadding ?? sp.page,
            vertical: sp.rowVertical,
          ),
          decoration: BoxDecoration(
            border: Border(
              top: topLine ? ct.hairline : BorderSide.none,
              bottom: bottomLine ? ct.hairline : BorderSide.none,
            ),
          ),
          child: Row(
            children: [
              if (leading != null) ...[leading!, SizedBox(width: sp.l - 2)],
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            title,
                            style: dimmed
                                ? ct.text.rowTitle.copyWith(color: c.muted)
                                : ct.text.rowTitle,
                          ),
                        ),
                        if (unread) ...[
                          SizedBox(width: sp.s),
                          SizedBox.square(
                            dimension: ct.sizes.unreadDot,
                            child: ColoredBox(color: c.orange),
                          ),
                        ],
                      ],
                    ),
                    if (subtitle != null) ...[
                      SizedBox(height: sp.xxs),
                      Text(subtitle!, style: ct.text.meta),
                    ],
                  ],
                ),
              ),
              if (value != null) ...[
                SizedBox(width: sp.m),
                Text(value!, style: ct.text.rowValue),
              ],
              if (trailing != null) ...[
                SizedBox(width: sp.s),
                trailing!,
              ] else if (onTap != null) ...[
                SizedBox(width: sp.s),
                Icon(Icons.chevron_right, color: c.faint, size: ct.sizes.icon),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// A section label in capitals above a group of rows (Profil settings).
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        ct.spacing.page,
        ct.spacing.xl,
        ct.spacing.page,
        ct.spacing.s,
      ),
      child: Semantics(
        header: true,
        child: Text(ct.text.upper(text), style: ct.text.label),
      ),
    );
  }
}
