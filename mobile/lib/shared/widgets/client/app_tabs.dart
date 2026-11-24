import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';

/// Full-width equal tabs under a header ("Moje karnety | Kup karnet"). The
/// selected tab is in ink at the selected weight with an ink underline.
class AppTabs extends StatelessWidget {
  const AppTabs({
    super.key,
    required this.labels,
    required this.selected,
    required this.onChanged,
  });

  final List<String> labels;
  final int selected;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    return DecoratedBox(
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Row(
        children: [
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: Semantics(
                selected: i == selected,
                button: true,
                child: InkWell(
                  onTap: () => onChanged(i),
                  child: Container(
                    constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
                    padding: EdgeInsets.only(
                      top: ct.spacing.xs,
                      bottom: ct.spacing.m,
                    ),
                    alignment: Alignment.bottomCenter,
                    decoration: BoxDecoration(
                      border: Border(
                        bottom: BorderSide(
                          color: i == selected ? c.ink : Colors.transparent,
                          width: ct.shape.tabIndicator,
                        ),
                      ),
                    ),
                    child: Text(
                      labels[i],
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: i == selected
                          ? ct.text.tab.copyWith(
                              fontWeight: ct.text.selectedWeight,
                            )
                          : ct.text.tab.copyWith(color: c.muted),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// One option of an [AppSegmented].
class AppSegment<T> {
  const AppSegment(this.value, this.label);

  final T value;
  final String label;
}

/// Two or more equal halves in a hairline frame ("Moje | Wszystkie"). The
/// selected half has an ink outline and the selected weight; nothing is
/// filled.
class AppSegmented<T> extends StatelessWidget {
  const AppSegmented({
    super.key,
    required this.segments,
    required this.selected,
    required this.onChanged,
  });

  final List<AppSegment<T>> segments;
  final T selected;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    return Container(
      height: ct.sizes.segmentHeight,
      decoration: BoxDecoration(border: Border.fromBorderSide(ct.hairline)),
      child: Row(
        children: [
          for (final segment in segments)
            Expanded(
              child: Semantics(
                selected: segment.value == selected,
                button: true,
                child: InkWell(
                  onTap: () => onChanged(segment.value),
                  child: Container(
                    alignment: Alignment.center,
                    padding: EdgeInsets.symmetric(horizontal: ct.spacing.s),
                    decoration: segment.value == selected
                        ? BoxDecoration(
                            border: Border.all(
                              color: c.ink,
                              width: ct.shape.focusOutline,
                            ),
                          )
                        : null,
                    child: Text(
                      segment.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: segment.value == selected
                          ? ct.text.segment.copyWith(
                              fontWeight: ct.text.selectedWeight,
                            )
                          : ct.text.segment.copyWith(color: c.muted),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
