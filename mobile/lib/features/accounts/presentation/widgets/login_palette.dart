import 'package:flutter/material.dart';
import 'package:mobile/app/theme/brand_colors.dart';

/// Colours of the login screen, one set for the light and one for the dark
/// system theme.
class LoginPalette {
  LoginPalette({
    required this.brightness,
    required this.accent,
    required this.field,
    required this.fieldBorder,
    required this.error,
    required this.button,
    required this.onButton,
    this.buttonGradient,
  });

  static final light = LoginPalette(
    brightness: Brightness.light,
    accent: BrandColors.teal500,
    field: Colors.white,
    fieldBorder: BorderSide.none,
    error: Color(0xFFBA1A1A),
    button: BrandColors.teal500,
    onButton: Colors.white,
    buttonGradient: const LinearGradient(
      colors: [BrandColors.teal500, BrandColors.teal700],
    ),
  );

  static final dark = LoginPalette(
    brightness: Brightness.dark,
    accent: BrandColors.teal300,
    field: Color(0x12FFFFFF),
    fieldBorder: const BorderSide(color: Color(0x7385C5C3), width: 1.2),
    error: Color(0xFFFFB4AB),
    button: BrandColors.teal300,
    onButton: BrandColors.teal900,
  );

  final Brightness brightness;
  final Color accent;
  final Color field;
  final BorderSide fieldBorder;
  final Color error;
  final Color button;
  final Color onButton;
  final Gradient? buttonGradient;

  late final ThemeData theme = _buildTheme();

  ThemeData _buildTheme() {
    const radius = BorderRadius.all(Radius.circular(14));
    return ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: BrandColors.teal500,
        brightness: brightness,
      ),
      inputDecorationTheme: InputDecorationThemeData(
        filled: true,
        fillColor: field,
        prefixIconColor: accent,
        border: _FieldBorder(borderRadius: radius, borderSide: fieldBorder),
        enabledBorder: _FieldBorder(
          borderRadius: radius,
          borderSide: fieldBorder,
        ),
        focusedBorder: _FieldBorder(
          borderRadius: radius,
          borderSide: BorderSide(color: accent, width: 2),
        ),
      ),
    );
  }
}

/// A rounded outline that keeps the floating label inside the field, as in
/// a filled field, instead of cutting it into the top edge.
class _FieldBorder extends OutlineInputBorder {
  const _FieldBorder({super.borderSide, super.borderRadius});

  @override
  bool get isOutline => false;

  @override
  _FieldBorder copyWith({
    BorderSide? borderSide,
    BorderRadius? borderRadius,
    double? gapPadding,
  }) {
    return _FieldBorder(
      borderSide: borderSide ?? this.borderSide,
      borderRadius: borderRadius ?? this.borderRadius,
    );
  }

  @override
  ShapeBorder? lerpFrom(ShapeBorder? a, double t) {
    if (a is _FieldBorder) {
      return _FieldBorder(
        borderSide: BorderSide.lerp(a.borderSide, borderSide, t),
        borderRadius: BorderRadius.lerp(a.borderRadius, borderRadius, t)!,
      );
    }
    return super.lerpFrom(a, t);
  }

  @override
  ShapeBorder? lerpTo(ShapeBorder? b, double t) {
    if (b is _FieldBorder) {
      return _FieldBorder(
        borderSide: BorderSide.lerp(borderSide, b.borderSide, t),
        borderRadius: BorderRadius.lerp(borderRadius, b.borderRadius, t)!,
      );
    }
    return super.lerpTo(b, t);
  }
}
