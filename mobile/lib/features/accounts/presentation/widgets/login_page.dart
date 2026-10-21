import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_background.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_palette.dart';

/// The page frame of the login-style screens: gradient background, themed
/// fields and system bar icons matching the system brightness.
class LoginPage extends StatelessWidget {
  const LoginPage({super.key, required this.builder});

  final Widget Function(BuildContext context, LoginPalette palette) builder;

  @override
  Widget build(BuildContext context) {
    final brightness = MediaQuery.platformBrightnessOf(context);
    final palette = brightness == Brightness.dark
        ? LoginPalette.dark
        : LoginPalette.light;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: brightness == Brightness.dark
          ? SystemUiOverlayStyle.light
          : SystemUiOverlayStyle.dark,
      child: Theme(
        data: palette.theme,
        child: Stack(
          children: [
            // Outside the scaffold, so the keyboard covers the background
            // instead of squeezing it.
            Positioned.fill(child: LoginBackground(brightness: brightness)),
            Scaffold(
              backgroundColor: Colors.transparent,
              body: SafeArea(
                child: Builder(builder: (context) => builder(context, palette)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The pill-shaped main button. A [dimmed] button looks disabled while
/// keeping its shape.
class LoginButton extends StatelessWidget {
  const LoginButton({
    super.key,
    required this.palette,
    required this.onPressed,
    required this.child,
    this.dimmed = false,
  });

  final LoginPalette palette;
  final VoidCallback? onPressed;
  final Widget child;
  final bool dimmed;

  @override
  Widget build(BuildContext context) {
    final gradient = dimmed ? null : palette.buttonGradient;
    return DecoratedBox(
      decoration: ShapeDecoration(
        shape: const StadiumBorder(),
        color: dimmed
            ? palette.button.withValues(alpha: 0.28)
            : (gradient == null ? palette.button : null),
        gradient: gradient,
      ),
      child: FilledButton(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: Colors.transparent,
          disabledBackgroundColor: Colors.transparent,
          foregroundColor: palette.onButton,
          disabledForegroundColor: palette.onButton.withValues(
            alpha: dimmed ? 0.6 : 1,
          ),
          shadowColor: Colors.transparent,
          minimumSize: const Size.fromHeight(54),
          shape: const StadiumBorder(),
          textStyle: palette.theme.textTheme.labelLarge?.copyWith(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
        child: child,
      ),
    );
  }
}

/// A centred text link under the main button.
class LoginLink extends StatelessWidget {
  const LoginLink({
    super.key,
    required this.palette,
    required this.label,
    required this.onPressed,
  });

  final LoginPalette palette;
  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: TextButton(
        onPressed: onPressed,
        style: TextButton.styleFrom(
          foregroundColor: palette.accent,
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
        child: Text(label),
      ),
    );
  }
}
