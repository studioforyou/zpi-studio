import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';

enum StatusTone {
  /// "Aktywny".
  ok,

  /// "Zaplanowany".
  scheduled,

  /// "Czeka na płatność", "Zawieszony".
  warning,

  /// "Wygasł", "Anulowany".
  off,
}

/// A round dot followed by a status label, both in the tone's colour.
class StatusDot extends StatelessWidget {
  const StatusDot({super.key, required this.label, required this.tone});

  final String label;
  final StatusTone tone;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final color = switch (tone) {
      StatusTone.ok => c.ok,
      StatusTone.scheduled => c.scheduled,
      StatusTone.warning => c.orange,
      StatusTone.off => c.muted,
    };
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: ct.sizes.statusDot,
          height: ct.sizes.statusDot,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        SizedBox(width: ct.spacing.s - 1),
        Flexible(
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: ct.text.status.copyWith(color: color),
          ),
        ),
      ],
    );
  }
}

/// A square orange count. Hidden when [count] is 0.
class CountBadge extends StatelessWidget {
  const CountBadge({super.key, required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    if (count <= 0) return const SizedBox.shrink();
    final ct = context.ct;
    return ExcludeSemantics(
      child: Container(
        constraints: BoxConstraints(
          minWidth: ct.sizes.badge,
          minHeight: ct.sizes.badge,
        ),
        padding: EdgeInsets.symmetric(horizontal: ct.spacing.xxs + 1),
        alignment: Alignment.center,
        color: ct.colors.orange,
        child: Text(count > 99 ? '99+' : '$count', style: ct.text.badge),
      ),
    );
  }
}

/// [child] with a [CountBadge] on its top right corner.
class WithBadge extends StatelessWidget {
  const WithBadge({super.key, required this.count, required this.child});

  final int count;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (count <= 0) return child;
    final ct = context.ct;
    final offset = ct.sizes.badge / 2;
    return Stack(
      clipBehavior: Clip.none,
      children: [
        child,
        Positioned(
          top: -offset + ct.spacing.xxs,
          right: -offset,
          child: CountBadge(count: count),
        ),
      ],
    );
  }
}

/// A full-width orange-tinted strip with one line of text and an underlined
/// action on the right (make-up credits, "Zwolniło się miejsce").
class InfoStrip extends StatelessWidget {
  const InfoStrip({
    super.key,
    required this.text,
    this.actionLabel,
    this.onAction,
    this.topGap = true,
  });

  final String text;
  final String? actionLabel;
  final VoidCallback? onAction;

  /// Adds the section gap above the strip.
  final bool topGap;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final textStyle = ct.text.strip;
    return Padding(
      padding: EdgeInsets.only(top: topGap ? sp.section : 0),
      child: Container(
        width: double.infinity,
        color: c.orangeTint,
        padding: EdgeInsets.fromLTRB(sp.page, sp.xs, sp.m, sp.xs),
        constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
        child: Row(
          children: [
            Expanded(
              child: Text(
                text,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: textStyle,
              ),
            ),
            if (actionLabel != null)
              InkWell(
                onTap: onAction,
                child: Container(
                  constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
                  padding: EdgeInsets.symmetric(horizontal: sp.xs + sp.xxs),
                  alignment: Alignment.center,
                  child: Text(
                    actionLabel!,
                    style: ct.text.buttonGhost.copyWith(
                      color: c.orangeText,
                      decoration: TextDecoration.underline,
                      decorationColor: c.orangeText,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// A square of initials (chat and person avatars, profile).
class SquareAvatar extends StatelessWidget {
  const SquareAvatar({
    super.key,
    required this.initials,
    required this.background,
    required this.foreground,
    this.size,
    this.style,
  });

  final String initials;
  final Color background;
  final Color foreground;

  /// Defaults to [ClientSizes.avatar].
  final double? size;
  final TextStyle? style;

  /// Up to two initials of [name].
  static String initialsOf(String name) {
    final parts = name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty);
    return parts.take(2).map((p) => p.characters.first.toUpperCase()).join();
  }

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final dimension = size ?? ct.sizes.avatar;
    return ExcludeSemantics(
      child: Container(
        width: dimension,
        height: dimension,
        color: background,
        alignment: Alignment.center,
        child: Text(
          initials,
          style: (style ?? ct.text.button).copyWith(color: foreground),
        ),
      ),
    );
  }
}
