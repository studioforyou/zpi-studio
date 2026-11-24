import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';

enum AppButtonKind {
  /// Filled. One per screen or sheet ("Zapisz się", "Potwierdź miejsce").
  primary,

  /// Framed in ink, ink label, no fill ("Historia wejść", "Kup kolejny").
  secondary,

  /// No frame, muted label; a low-priority action beside a secondary one.
  ghost,

  /// The secondary layout with an orange label ("Wypisz się").
  destructive,
}

/// A button of the client app. Sizes, colours and text come from the client
/// theme.
class AppButton extends StatelessWidget {
  const AppButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.kind = AppButtonKind.secondary,
    this.small = false,
    this.icon,
    this.expand = false,
    this.loading = false,
  });

  const AppButton.primary({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.expand = true,
    this.loading = false,
  }) : kind = AppButtonKind.primary,
       small = false;

  const AppButton.ghost({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.loading = false,
  }) : kind = AppButtonKind.ghost,
       small = false,
       expand = false;

  final String label;

  /// `null` disables the button.
  final VoidCallback? onPressed;
  final AppButtonKind kind;

  /// The lower secondary button used in pairs and inside cards.
  final bool small;
  final IconData? icon;

  /// Whether the button takes the full width.
  final bool expand;

  /// Shows a progress indicator instead of the label and ignores taps.
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final s = ct.sizes;
    final enabled = onPressed != null && !loading;

    final (height, padding, text) = switch (kind) {
      AppButtonKind.primary => (
        s.buttonPrimaryHeight,
        s.buttonPadding,
        ct.text.buttonLarge,
      ),
      AppButtonKind.ghost => (
        s.buttonGhostHeight,
        ct.spacing.xs + ct.spacing.xxs,
        ct.text.buttonGhost,
      ),
      _ when small => (
        s.buttonSmallHeight,
        s.buttonSmallPadding,
        ct.text.button,
      ),
      _ => (s.buttonHeight, s.buttonPadding, ct.text.button),
    };

    final Color foreground;
    final Color background;
    BorderSide side = BorderSide.none;
    switch (kind) {
      case AppButtonKind.primary:
        foreground = enabled ? c.onPrimary : c.muted;
        background = enabled ? c.primary : c.line;
      case AppButtonKind.ghost:
        foreground = enabled ? c.muted : c.faint;
        background = Colors.transparent;
      case AppButtonKind.secondary:
      case AppButtonKind.destructive:
        final label = kind == AppButtonKind.destructive ? c.orange : c.ink;
        foreground = enabled ? label : c.faint;
        background = Colors.transparent;
        side = BorderSide(
          color: enabled ? c.ink : c.faint,
          width: ct.shape.hairline,
        );
    }

    final content = loading
        ? SizedBox.square(
            dimension: text.fontSize,
            child: CircularProgressIndicator(
              strokeWidth: ct.shape.tabIndicator,
              color: foreground,
            ),
          )
        : Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (icon != null) ...[
                Icon(
                  icon,
                  size: text.fontSize! + ct.spacing.xs,
                  color: foreground,
                ),
                SizedBox(width: ct.spacing.s - 1),
              ],
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: text.copyWith(color: foreground),
                ),
              ),
            ],
          );

    final button = TextButton(
      onPressed: enabled ? onPressed : null,
      style: ButtonStyle(
        minimumSize: WidgetStatePropertyAll(Size(height, height)),
        maximumSize: WidgetStatePropertyAll(Size(double.infinity, height)),
        padding: WidgetStatePropertyAll(
          EdgeInsets.symmetric(horizontal: padding),
        ),
        backgroundColor: WidgetStatePropertyAll(background),
        foregroundColor: WidgetStatePropertyAll(foreground),
        side: WidgetStatePropertyAll(side),
        shape: WidgetStatePropertyAll(ct.boxShape),
        overlayColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.pressed)
              ? c.ink.withValues(alpha: 0.06)
              : Colors.transparent,
        ),
        splashFactory: NoSplash.splashFactory,
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      child: content,
    );

    final semantic = loading
        ? Semantics(label: label, button: true, enabled: false, child: button)
        : button;
    return expand
        ? SizedBox(width: double.infinity, child: semantic)
        : semantic;
  }
}

/// Two secondary buttons side by side, each [ClientSizes.buttonPairWidth]
/// wide, centred. A single button keeps the same width.
class AppButtonPair extends StatelessWidget {
  const AppButtonPair({super.key, required this.first, this.second});

  final AppButton first;
  final AppButton? second;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final width = ct.sizes.buttonPairWidth;
    return LayoutBuilder(
      builder: (context, constraints) {
        final gap = ct.spacing.s;
        final fits = constraints.maxWidth >= 2 * width + gap;
        Widget slot(AppButton button) => fits
            ? SizedBox(width: width, child: button)
            : Expanded(child: button);
        return Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            slot(first),
            if (second != null) ...[SizedBox(width: gap), slot(second!)],
          ],
        );
      },
    );
  }
}
