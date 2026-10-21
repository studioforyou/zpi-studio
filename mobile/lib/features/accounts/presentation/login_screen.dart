import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_page.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_palette.dart';
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
    return LoginPage(
      builder: (context, palette) => Stack(
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
              onPressed: () => context.push(pingPath),
            ),
          ),
        ],
      ),
    );
  }

  Widget _form(AppLocalizations l10n, LoginPalette palette) {
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
          LoginButton(
            palette: palette,
            onPressed: _submitting ? null : _login,
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
          LoginLink(
            palette: palette,
            label: l10n.loginForgotPassword,
            onPressed: () => context.push(
              resetPasswordPath,
              extra: _emailController.text.trim(),
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
