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
