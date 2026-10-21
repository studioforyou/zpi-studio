import 'package:flutter/material.dart';
import 'package:mobile/app/theme/brand_colors.dart';

/// Teal gradient behind the login form.
class LoginBackground extends StatelessWidget {
  const LoginBackground({super.key, required this.brightness});

  final Brightness brightness;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: brightness == Brightness.dark
            ? _darkGradient
            : _lightGradient,
      ),
      child: const SizedBox.expand(),
    );
  }
}

const _lightGradient = LinearGradient(
  begin: Alignment.topLeft,
  end: Alignment(1, 0.8),
  stops: [0, 0.55, 1],
  colors: [Color(0xFFE6F0F0), Color(0xFFBFDDDC), Color(0xFF95C8C7)],
);

const _darkGradient = LinearGradient(
  begin: Alignment.topLeft,
  end: Alignment.bottomCenter,
  stops: [0, 0.5, 1],
  colors: [Color(0xFF2F7584), BrandColors.teal900, Color(0xFF0A1D21)],
);
