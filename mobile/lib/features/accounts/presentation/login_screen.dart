import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/theme/brand_colors.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_background.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();

  bool _submitting = false;
  AuthFailureReason? _failure;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    setState(() {
      _submitting = true;
      _failure = null;
    });
    try {
      await ref
          .read(authControllerProvider.notifier)
          .login(_emailController.text, _passwordController.text);
    } on AuthFailure catch (e) {
      _failure = e.reason;
    } catch (_) {
      _failure = AuthFailureReason.unknown;
    }
    // On success the router has already left this screen.
    if (mounted) {
      setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final brightness = MediaQuery.platformBrightnessOf(context);
    final palette = brightness == Brightness.dark
        ? _LoginPalette.dark
        : _LoginPalette.light;

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
                child: Stack(
                  children: [
                    Center(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.all(24),
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 400),
                          child: _form(l10n, palette),
                        ),
                      ),
                    ),
                    Align(
                      alignment: Alignment.topRight,
                      child: IconButton(
                        icon: const Icon(Icons.network_ping),
                        color: palette.accent,
                        tooltip: l10n.pingTitle,
                        onPressed: () => context.push('/ping'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _form(AppLocalizations l10n, _LoginPalette palette) {
    final errorStyle = TextStyle(
      color: palette.error,
      fontSize: 14,
      fontWeight: FontWeight.w500,
    );

    return AutofillGroup(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: 16,
        children: [
          TextField(
            controller: _emailController,
            keyboardType: TextInputType.emailAddress,
            autofillHints: const [AutofillHints.email],
            textInputAction: TextInputAction.next,
            decoration: InputDecoration(
              labelText: l10n.loginEmailLabel,
              prefixIcon: const Icon(Icons.mail_outline),
            ),
          ),
          TextField(
            controller: _passwordController,
            obscureText: true,
            autofillHints: const [AutofillHints.password],
            onSubmitted: (_) => _submitting ? null : _login(),
            decoration: InputDecoration(
              labelText: l10n.loginPasswordLabel,
              prefixIcon: const Icon(Icons.lock_outline),
            ),
          ),
          // Every message is laid out, only the current one is shown:
          // the slot is as tall as the longest message, so the form
          // does not move when one appears.
          Stack(
            children: [
              for (final reason in AuthFailureReason.values)
                Visibility(
                  visible: reason == _failure,
                  maintainState: true,
                  maintainAnimation: true,
                  maintainSize: true,
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: 8,
                    children: [
                      Icon(Icons.error_outline, size: 18, color: palette.error),
                      Expanded(
                        child: Text(
                          _failureMessage(l10n, reason),
                          style: errorStyle,
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
          DecoratedBox(
            decoration: ShapeDecoration(
              shape: const StadiumBorder(),
              color: palette.buttonGradient == null ? palette.button : null,
              gradient: palette.buttonGradient,
            ),
            child: FilledButton(
              onPressed: _submitting ? null : _login,
              style: FilledButton.styleFrom(
                backgroundColor: Colors.transparent,
                disabledBackgroundColor: Colors.transparent,
                foregroundColor: palette.onButton,
                disabledForegroundColor: palette.onButton,
                shadowColor: Colors.transparent,
                minimumSize: const Size.fromHeight(54),
                shape: const StadiumBorder(),
                textStyle: palette.theme.textTheme.labelLarge?.copyWith(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
              child: _submitting
                  ? SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: palette.onButton,
                      ),
                    )
                  : Text(l10n.loginButton),
            ),
          ),
        ],
      ),
    );
  }

  static String _failureMessage(
    AppLocalizations l10n,
    AuthFailureReason reason,
  ) {
    return switch (reason) {
      AuthFailureReason.invalidCredentials => l10n.loginErrorInvalidCredentials,
      AuthFailureReason.tooManyAttempts => l10n.loginErrorTooManyAttempts,
      AuthFailureReason.network => l10n.loginErrorNetwork,
      AuthFailureReason.accountNotFound => l10n.loginErrorAccountNotFound,
      AuthFailureReason.pendingApproval => l10n.loginErrorPendingApproval,
      AuthFailureReason.accountInactive => l10n.loginErrorAccountInactive,
      AuthFailureReason.noSupportedRole => l10n.loginErrorNoSupportedRole,
      AuthFailureReason.unknown => l10n.loginErrorUnknown,
    };
  }
}

/// Colours of the login screen, one set for the light and one for the dark
/// system theme.
class _LoginPalette {
  _LoginPalette({
    required this.brightness,
    required this.accent,
    required this.field,
    required this.fieldBorder,
    required this.error,
    required this.button,
    required this.onButton,
    this.buttonGradient,
  });

  static final light = _LoginPalette(
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

  static final dark = _LoginPalette(
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
