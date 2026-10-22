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

/// Height of a text field of the login-style screens. The reset screen
/// reserves it for its heading so both screens share one grid.
const loginFieldHeight = 56.0;

/// Room for one message under the fields. It is as tall as the longest
/// message, so nothing moves when a message appears and the button keeps the
/// same place on every login-style screen.
const loginMessageSlotHeight = 48.0;

class LoginMessageSlot extends StatelessWidget {
  const LoginMessageSlot({super.key, this.message});

  final Widget? message;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: loginMessageSlotHeight,
      child: Align(alignment: Alignment.topLeft, child: message),
    );
  }
}

/// An error, or with [success] a confirmation, shown in a [LoginMessageSlot].
class LoginMessage extends StatelessWidget {
  const LoginMessage({
    super.key,
    required this.palette,
    required this.text,
    this.success = false,
  });

  final LoginPalette palette;
  final String text;
  final bool success;

  @override
  Widget build(BuildContext context) {
    final color = success ? palette.accent : palette.error;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 8,
      children: [
        Icon(
          success ? Icons.check_circle_outline : Icons.error_outline,
          size: 18,
          color: color,
        ),
        Expanded(
          child: Text(
            text,
            style: TextStyle(
              color: color,
              fontSize: 14,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }
}

/// Space between the main button and the link under it.
const loginLinkGap = 12.0;

/// A centred text link right under the main button.
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
    return Padding(
      padding: const EdgeInsets.only(top: loginLinkGap),
      child: Center(
        child: TextButton(
          onPressed: onPressed,
          style: TextButton.styleFrom(
            foregroundColor: palette.accent,
            minimumSize: Size.zero,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            textStyle: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
          child: Text(label),
        ),
      ),
    );
  }
}
