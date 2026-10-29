import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/features/accounts/presentation/widgets/shrink_to_fit_text.dart';

void main() {
  Future<double> fontSizeIn(
    WidgetTester tester, {
    required String text,
    required double width,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: width,
            child: ShrinkToFitText(
              text,
              minFontSize: 10,
              style: const TextStyle(fontSize: 20),
            ),
          ),
        ),
      ),
    );
    expect(tester.takeException(), isNull);
    return tester.widget<Text>(find.text(text)).style!.fontSize!;
  }

  testWidgets('text that fits keeps its size', (tester) async {
    expect(await fontSizeIn(tester, text: 'ANNA', width: 300), 20);
  });

  testWidgets('a long text gets smaller to fit on one line', (tester) async {
    const text = 'KAROLINA SZYMAŃSKA-WIŚNIEWSKA';
    final size = await fontSizeIn(tester, text: text, width: 400);

    expect(size, lessThan(20));
    expect(size, greaterThanOrEqualTo(10));
    final paragraph = tester.renderObject<RenderParagraph>(find.text(text));
    expect(paragraph.didExceedMaxLines, isFalse);
  });

  testWidgets('a single long word gets smaller too', (tester) async {
    // The test font is 1 em wide per letter: 9 letters fit 120 px below 13.4.
    final size = await fontSizeIn(tester, text: 'KATARZYNA', width: 120);

    expect(size, lessThanOrEqualTo(120 / 9));
    expect(size, greaterThan(10));
  });

  testWidgets('it never goes below the minimum size', (tester) async {
    final size = await fontSizeIn(
      tester,
      text: 'KONSTANTYNOPOLITAŃCZYKIEWICZÓWNA KOWALSKA',
      width: 60,
    );

    expect(size, 10);
  });

  Future<Text> pumpTwoLines(WidgetTester tester, {required String text}) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: SizedBox(
            width: 100,
            child: ShrinkToFitText(
              text,
              minFontSize: 10,
              maxLines: 2,
              style: const TextStyle(fontSize: 20),
            ),
          ),
        ),
      ),
    );
    expect(tester.takeException(), isNull);
    return tester.widget<Text>(find.text(text));
  }

  testWidgets('text that fits one line when smaller stays on one line', (
    tester,
  ) async {
    // The test font is 1 em wide per letter: 8 letters fit 100 px at 12.5.
    final widget = await pumpTwoLines(tester, text: 'KAROLINA');

    expect(widget.maxLines, 1);
    expect(widget.style!.fontSize, greaterThan(10));
  });

  testWidgets('text too long at the minimum wraps onto the second line', (
    tester,
  ) async {
    const text = 'KAROLINA SZYMAŃSKA';
    final widget = await pumpTwoLines(tester, text: text);

    expect(widget.style!.fontSize, 10);
    expect(widget.maxLines, 2);
    final paragraph = tester.renderObject<RenderParagraph>(find.text(text));
    expect(paragraph.didExceedMaxLines, isFalse);
    expect(paragraph.size.height, greaterThan(10 * 1.5));
  });

  testWidgets('text too long for two lines ends with an ellipsis', (
    tester,
  ) async {
    const text = 'KONSTANTY MAKSYMILIAN SZYMAŃSKI WIŚNIEWSKI';
    final widget = await pumpTwoLines(tester, text: text);

    expect(widget.maxLines, 2);
    expect(widget.overflow, TextOverflow.ellipsis);
    final paragraph = tester.renderObject<RenderParagraph>(find.text(text));
    expect(paragraph.didExceedMaxLines, isTrue);
  });

  testWidgets('measures with the font the text is drawn in', (tester) async {
    // Real Roboto from the Flutter SDK: the test font hides measuring with
    // the wrong font, because every font falls back to it.
    final fonts = Directory(
      '${Platform.environment['FLUTTER_ROOT']}'
      '/bin/cache/artifacts/material_fonts',
    );
    await tester.runAsync(() async {
      // The file is "roboto-medium.ttf" in some SDK caches and
      // "Roboto-Medium.ttf" in others, and Linux file names are
      // case-sensitive.
      final roboto = fonts.listSync().whereType<File>().firstWhere(
        (file) =>
            file.uri.pathSegments.last.toLowerCase() == 'roboto-medium.ttf',
      );
      final loader = FontLoader('Roboto')
        ..addFont(
          roboto.readAsBytes().then((bytes) => ByteData.sublistView(bytes)),
        );
      await loader.load();
    });
    const text = 'Katarzyna';
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultTextStyle(
          style: const TextStyle(fontFamily: 'Roboto'),
          child: Center(
            child: SizedBox(
              width: 100,
              child: ShrinkToFitText(
                text,
                minFontSize: 10,
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ),
        ),
      ),
    );

    final size = tester.widget<Text>(find.text(text)).style!.fontSize!;
    final paragraph = tester.renderObject<RenderParagraph>(find.text(text));
    // Shrunk just enough: smaller than asked, far above the minimum, and the
    // whole name is drawn.
    expect(size, lessThan(24));
    expect(size, greaterThan(18));
    expect(paragraph.didExceedMaxLines, isFalse);
    expect(
      paragraph.getMaxIntrinsicWidth(double.infinity),
      lessThanOrEqualTo(100),
    );
  }, skip: Platform.environment['FLUTTER_ROOT'] == null);
}
