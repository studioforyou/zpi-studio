import 'package:flutter/material.dart';
import 'package:mobile/app/theme/brand_colors.dart';

/// Teal gradient with layered waves along the bottom edge, behind the login form.
class LoginBackground extends StatelessWidget {
  const LoginBackground({super.key, required this.brightness});

  final Brightness brightness;

  @override
  Widget build(BuildContext context) {
    final dark = brightness == Brightness.dark;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: dark ? _darkGradient : _lightGradient,
      ),
      child: CustomPaint(
        painter: _WavesPainter(dark ? _darkWaves : _lightWaves),
        child: const SizedBox.expand(),
      ),
    );
  }
}

const _lightGradient = LinearGradient(
  begin: Alignment.topLeft,
  end: Alignment(1, 0.8),
  stops: [0, 0.55, 1],
  colors: [BrandColors.teal50, Color(0xFFC8E2E1), Color(0xFFAED6D5)],
);

const _darkGradient = LinearGradient(
  begin: Alignment.topLeft,
  end: Alignment.bottomCenter,
  stops: [0, 0.5, 1],
  colors: [Color(0xFF296674), BrandColors.teal900, Color(0xFF0D2328)],
);

final _lightWaves = [
  _Wave(
    top: 120,
    amplitude: 12,
    phase: 0.2,
    period: 360,
    color: BrandColors.teal50,
  ),
  _Wave(
    top: 92,
    amplitude: 14,
    phase: -0.4,
    period: 460,
    color: BrandColors.teal100.withValues(alpha: 0.9),
  ),
  _Wave(
    top: 62,
    amplitude: 12,
    phase: 0.3,
    period: 320,
    color: BrandColors.teal300.withValues(alpha: 0.8),
  ),
  _Wave(
    top: 34,
    amplitude: 10,
    phase: 0,
    period: 410,
    color: BrandColors.teal500.withValues(alpha: 0.85),
  ),
];

final _darkWaves = [
  _Wave(
    top: 110,
    amplitude: 12,
    phase: 0.2,
    period: 360,
    color: BrandColors.teal500.withValues(alpha: 0.18),
  ),
  _Wave(
    top: 76,
    amplitude: 14,
    phase: -0.4,
    period: 460,
    color: BrandColors.teal300.withValues(alpha: 0.12),
  ),
  _Wave(
    top: 40,
    amplitude: 12,
    phase: 0.3,
    period: 320,
    color: BrandColors.teal500.withValues(alpha: 0.2),
  ),
];

/// One wave band, filled down to the bottom edge.
class _Wave {
  const _Wave({
    required this.top,
    required this.amplitude,
    required this.phase,
    required this.period,
    required this.color,
  });

  /// Distance of the wave's middle line from the bottom edge.
  final double top;
  final double amplitude;

  /// Shifts the points where the wave crosses its middle line up or down,
  /// as a fraction of [amplitude], so that the layers do not run in parallel.
  final double phase;
  final double period;
  final Color color;
}

class _WavesPainter extends CustomPainter {
  const _WavesPainter(this.waves);

  final List<_Wave> waves;

  @override
  void paint(Canvas canvas, Size size) {
    for (final wave in waves) {
      final middle = size.height - wave.top;
      final node = middle + wave.amplitude * wave.phase;
      final half = wave.period / 2;
      final path = Path()..moveTo(0, node);
      var x = 0.0;
      for (var i = 0; x < size.width; i++) {
        final crest = middle + (i.isEven ? -1 : 1) * wave.amplitude * 1.3;
        path.cubicTo(
          x + wave.period / 6,
          crest,
          x + half - wave.period / 6,
          crest,
          x + half,
          node,
        );
        x += half;
      }
      path
        ..lineTo(x, size.height)
        ..lineTo(0, size.height)
        ..close();
      canvas.drawPath(path, Paint()..color = wave.color);
    }
  }

  @override
  bool shouldRepaint(_WavesPainter oldDelegate) => oldDelegate.waves != waves;
}
