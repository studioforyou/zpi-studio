import 'package:flutter/material.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// The full-width bar with the person's next class; tapping it opens the
/// class.
class NextClassBar extends StatelessWidget {
  const NextClassBar({
    super.key,
    required this.when,
    required this.title,
    required this.onTap,
  });

  /// "Dziś 17:00 · za 36 min".
  final String when;
  final String title;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Semantics(
      button: true,
      child: Material(
        color: c.primary,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: EdgeInsets.fromLTRB(sp.page, sp.m, sp.page, sp.section),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        when,
                        style: ct.text.body.copyWith(
                          color: c.onPrimary.withValues(alpha: 0.85),
                        ),
                      ),
                      SizedBox(height: sp.xxs + 1),
                      Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: ct.text.nextClass.copyWith(color: c.onPrimary),
                      ),
                    ],
                  ),
                ),
                Icon(Icons.chevron_right, color: c.onPrimary),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// One choice of a [PersonFilter].
class PersonChoice<T> {
  const PersonChoice({
    required this.value,
    required this.label,
    this.everyone = false,
  });

  final T value;
  final String label;

  /// The choice of the whole family, set apart on the right.
  final bool everyone;
}

/// Framed choices of whose classes to show ("Ja | Zosia   Wszyscy").
class PersonFilter<T> extends StatelessWidget {
  const PersonFilter({
    super.key,
    required this.choices,
    required this.selected,
    required this.onChanged,
  });

  final List<PersonChoice<T>> choices;
  final T selected;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    Widget box(PersonChoice<T> choice) {
      final on = choice.value == selected;
      return Expanded(
        child: Semantics(
          selected: on,
          button: true,
          child: InkWell(
            onTap: () => onChanged(choice.value),
            child: Container(
              height: ct.sizes.segmentHeight,
              padding: EdgeInsets.symmetric(horizontal: ct.spacing.s),
              decoration: BoxDecoration(
                border: Border.all(
                  color: on ? c.ink : c.line,
                  width: ct.shape.focusOutline,
                ),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    choice.everyone
                        ? Icons.groups_outlined
                        : Icons.person_outline,
                    size: ct.sizes.icon - 4,
                    color: on ? c.ink : c.muted,
                  ),
                  SizedBox(width: ct.spacing.s - 2),
                  Flexible(
                    child: Text(
                      choice.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: on
                          ? ct.text.segment.copyWith(
                              fontWeight: ct.text.selectedWeight,
                            )
                          : ct.text.segment.copyWith(color: c.muted),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    final people = [
      for (final c in choices)
        if (!c.everyone) c,
    ];
    final everyone = [
      for (final c in choices)
        if (c.everyone) c,
    ];
    return Row(
      children: [
        ...people.map(box),
        if (everyone.isNotEmpty) ...[
          SizedBox(width: ct.spacing.s + 1),
          ...everyone.map(box),
        ],
      ],
    );
  }
}
