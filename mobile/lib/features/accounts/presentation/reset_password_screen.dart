import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_failure.dart';
import 'package:mobile/core/auth/auth_repository.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_page.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_palette.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// How long the user has to wait before asking for the reset e-mail again.
const passwordResetCooldown = Duration(seconds: 30);

/// Lifts the heading and the intro above the e-mail field.
const resetHeadingBottomGap = 12.0;

/// What the message slot under the e-mail field can say.
enum _Notice { sent, invalidEmail, tooManyAttempts, network, unknown }

class ResetPasswordScreen extends ConsumerStatefulWidget {
  const ResetPasswordScreen({super.key, this.initialEmail = ''});

  /// E-mail already typed on the login screen.
  final String initialEmail;

  @override
  ConsumerState<ResetPasswordScreen> createState() =>
      _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends ConsumerState<ResetPasswordScreen> {
  late final _emailController = TextEditingController(
    text: widget.initialEmail,
  );

  Timer? _ticker;
  bool _submitting = false;
  bool _requested = false;
  int _secondsLeft = 0;
  _Notice? _notice;

  @override
  void dispose() {
    _ticker?.cancel();
    _emailController.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (_submitting || _secondsLeft > 0) {
      return;
    }
    final email = _emailController.text.trim();
    if (email.isEmpty) {
      setState(() => _notice = _Notice.invalidEmail);
      return;
    }
    setState(() {
      _submitting = true;
      _notice = null;
    });
    _Notice notice;
    try {
      await ref.read(authRepositoryProvider).sendPasswordReset(email);
      notice = _Notice.sent;
    } on PasswordResetFailure catch (e) {
      notice = switch (e.reason) {
        PasswordResetFailureReason.invalidEmail => _Notice.invalidEmail,
        PasswordResetFailureReason.tooManyAttempts => _Notice.tooManyAttempts,
        PasswordResetFailureReason.network => _Notice.network,
        PasswordResetFailureReason.unknown => _Notice.unknown,
      };
    } catch (_) {
      notice = _Notice.unknown;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _submitting = false;
      _notice = notice;
      if (notice == _Notice.sent) {
        _requested = true;
        _startCooldown();
      }
    });
  }

  void _startCooldown() {
    _ticker?.cancel();
    _secondsLeft = passwordResetCooldown.inSeconds;
    _ticker = Timer.periodic(const Duration(seconds: 1), (timer) {
      setState(() => _secondsLeft--);
      if (_secondsLeft <= 0) {
        timer.cancel();
      }
    });
  }

  void _backToLogin() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go(loginPath);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return LoginPage(
      builder: (context, palette) => Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 400),
            child: _form(l10n, palette),
          ),
        ),
      ),
    );
  }

  Widget _form(AppLocalizations l10n, LoginPalette palette) {
    final theme = Theme.of(context);
    final waiting = _secondsLeft > 0;

    // Same rows as the login form (field, field, message, button, link), so
    // the e-mail field and the button stay where the password field and the
    // login button were. The heading takes the place of the first field and
    // grows upwards.
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 16,
      children: [
        SizedBox(
          height: loginFieldHeight,
          child: OverflowBox(
            alignment: Alignment.bottomLeft,
            minHeight: 0,
            maxHeight: double.infinity,
            child: Padding(
              padding: const EdgeInsets.only(bottom: resetHeadingBottomGap),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 8,
                children: [
                  Text(
                    l10n.resetTitle,
                    style: theme.textTheme.headlineMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  Text(l10n.resetIntro, style: theme.textTheme.bodyMedium),
                ],
              ),
            ),
          ),
        ),
        TextField(
          controller: _emailController,
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.email],
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _send(),
          decoration: InputDecoration(
            labelText: l10n.loginEmailLabel,
            prefixIcon: const Icon(Icons.mail_outline),
          ),
        ),
        LoginMessageSlot(
          message: _notice == null
              ? null
              : LoginMessage(
                  palette: palette,
                  text: _noticeText(l10n, _notice!),
                  success: _notice == _Notice.sent,
                ),
        ),
        Column(
          children: [
            LoginButton(
              palette: palette,
              dimmed: waiting,
              onPressed: _submitting || waiting ? null : _send,
              child: _submitting
                  ? SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: palette.onButton,
                      ),
                    )
                  : Text(
                      !_requested
                          ? l10n.resetSendButton
                          : waiting
                          ? l10n.resetResendCountdown(_secondsLeft)
                          : l10n.resetResendButton,
                    ),
            ),
            LoginLink(
              palette: palette,
              label: l10n.resetBackToLogin,
              onPressed: _backToLogin,
            ),
          ],
        ),
      ],
    );
  }

  static String _noticeText(AppLocalizations l10n, _Notice notice) {
    return switch (notice) {
      _Notice.sent => l10n.resetSent,
      _Notice.invalidEmail => l10n.resetErrorInvalidEmail,
      _Notice.tooManyAttempts => l10n.resetErrorTooManyAttempts,
      _Notice.network => l10n.resetErrorNetwork,
      _Notice.unknown => l10n.resetErrorUnknown,
    };
  }
}
