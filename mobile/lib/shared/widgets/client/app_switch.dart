import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';

/// A square switch. Off: hairline frame with a faint knob on the left. On:
/// primary fill with the knob on the right.
class AppSwitch extends StatelessWidget {
  const AppSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.semanticLabel,
  });

  final bool value;

  /// `null` disables the switch.
  final ValueChanged<bool>? onChanged;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final s = ct.sizes;
    final enabled = onChanged != null;
    final inset = (s.switchHeight - s.switchKnob) / 2;
    return Semantics(
      toggled: value,
      enabled: enabled,
      label: semanticLabel,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? () => onChanged!(!value) : null,
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: s.tapTarget),
          child: Center(
            widthFactor: 1,
            child: AnimatedContainer(
              duration: ct.motion.short,
              width: s.switchWidth,
              height: s.switchHeight,
              decoration: BoxDecoration(
                color: value ? c.primary : Colors.transparent,
                border: Border.all(
                  color: value ? c.primary : c.line,
                  width: ct.shape.hairline,
                ),
              ),
              child: AnimatedAlign(
                duration: ct.motion.short,
                curve: Curves.easeOut,
                alignment: value ? Alignment.centerRight : Alignment.centerLeft,
                child: Padding(
                  padding: EdgeInsets.all(inset - ct.shape.hairline),
                  child: SizedBox.square(
                    dimension: s.switchKnob,
                    child: ColoredBox(
                      color: !enabled
                          ? c.line
                          : value
                          ? c.switchKnobOn
                          : c.faint,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A square checkbox: an ink frame, filled with ink and a tick when on.
class AppCheckbox extends StatelessWidget {
  const AppCheckbox({super.key, required this.value, this.enabled = true});

  final bool value;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final frame = enabled ? c.ink : c.faint;
    return Container(
      width: ct.sizes.checkbox,
      height: ct.sizes.checkbox,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: value ? frame : null,
        border: Border.all(color: frame, width: ct.shape.checkboxBorder),
      ),
      child: value
          ? Icon(Icons.check, size: ct.sizes.checkboxGlyph, color: c.background)
          : null,
    );
  }
}

/// A row with a checkbox, a title and an optional hint, toggled by a tap
/// anywhere on it. Hairline under the row.
class AppCheckRow extends StatelessWidget {
  const AppCheckRow({
    super.key,
    required this.value,
    required this.title,
    this.hint,
    required this.onChanged,
  });

  final bool value;
  final String title;
  final String? hint;

  /// `null` disables the row.
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return MergeSemantics(
      child: Semantics(
        checked: value,
        enabled: onChanged != null,
        child: InkWell(
          onTap: onChanged == null ? null : () => onChanged!(!value),
          child: Container(
            padding: EdgeInsets.symmetric(horizontal: sp.page, vertical: sp.l),
            decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                AppCheckbox(value: value, enabled: onChanged != null),
                SizedBox(width: sp.m + sp.xxs),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title, style: ct.text.consequenceTitle),
                      if (hint != null) ...[
                        SizedBox(height: sp.xxs + 1),
                        Text(hint!, style: ct.text.meta),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
