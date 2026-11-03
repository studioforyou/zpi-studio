import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Text that makes its font smaller, down to [minFontSize], to stay on one
/// line within the available width. Text too long even at [minFontSize]
/// wraps onto up to [maxLines] lines at that size, and only what does not fit
/// there is cut with an ellipsis.
class ShrinkToFitText extends StatelessWidget {
  const ShrinkToFitText(
    this.text, {
    super.key,
    required this.style,
    required this.minFontSize,
    this.maxLines = 1,
    this.textAlign = TextAlign.start,
  });

  final String text;

  /// The style at full size; its font size is the starting point.
  final TextStyle style;
  final double minFontSize;

  /// Lines the text may take once it is at [minFontSize].
  final int maxLines;
  final TextAlign textAlign;

  /// How much smaller each try is.
  static const _step = 0.5;

  @override
  Widget build(BuildContext context) {
    final textScaler = MediaQuery.textScalerOf(context);
    final textDirection = Directionality.of(context);
    // Measured with the same font family, weight etc. as the text will be
    // drawn with, which [Text] takes from the surrounding default style.
    final fullStyle = DefaultTextStyle.of(context).style.merge(style);

    return LayoutBuilder(
      builder: (context, constraints) {
        bool fitsOneLine(double fontSize) {
          final painter = TextPainter(
            text: TextSpan(
              text: text,
              style: fullStyle.copyWith(fontSize: fontSize),
            ),
            textDirection: textDirection,
            textScaler: textScaler,
          )..layout();
          // The whole text on one line, without any wrapping.
          final fits = painter.width <= constraints.maxWidth;
          painter.dispose();
          return fits;
        }

        var fontSize = fullStyle.fontSize ?? 14;
        var fits = fitsOneLine(fontSize);
        while (!fits && fontSize > minFontSize) {
          fontSize = math.max(minFontSize, fontSize - _step);
          fits = fitsOneLine(fontSize);
        }
        return Text(
          text,
          textAlign: textAlign,
          maxLines: fits ? 1 : maxLines,
          overflow: TextOverflow.ellipsis,
          style: style.copyWith(fontSize: fontSize),
        );
      },
    );
  }
}
