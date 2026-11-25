import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/theme/client/client_theme.dart';

/// "‹ Listopad 2026 ›": the month shown and arrows to the months around it.
/// A `null` callback greys its arrow out.
class MonthNav extends StatelessWidget {
  const MonthNav({
    super.key,
    required this.month,
    required this.previousTooltip,
    required this.nextTooltip,
    this.onPrevious,
    this.onNext,
  });

  final DateTime month;
  final String previousTooltip;
  final String nextTooltip;
  final VoidCallback? onPrevious;
  final VoidCallback? onNext;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final title = DateFormat('LLLL yyyy', 'pl').format(month);
    Widget arrow(IconData icon, String tooltip, VoidCallback? onPressed) =>
        IconButton(
          tooltip: tooltip,
          constraints: BoxConstraints.tight(Size.square(ct.sizes.tapTarget)),
          icon: Icon(
            icon,
            size: ct.sizes.icon,
            color: onPressed == null ? c.faint : c.ink,
          ),
          onPressed: onPressed,
        );
    return Padding(
      padding: EdgeInsets.symmetric(
        horizontal: ct.spacing.s,
        vertical: ct.spacing.xs,
      ),
      child: Row(
        children: [
          arrow(Icons.chevron_left, previousTooltip, onPrevious),
          Expanded(
            child: Text(
              title.characters.first.toUpperCase() +
                  title.characters.skip(1).toString(),
              textAlign: TextAlign.center,
              style: ct.text.monthTitle,
            ),
          ),
          arrow(Icons.chevron_right, nextTooltip, onNext),
        ],
      ),
    );
  }
}
