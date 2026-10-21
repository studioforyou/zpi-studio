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
            alignment: Alignment.topLeft,
            child: BackButton(color: palette.accent, onPressed: _backToLogin),
          ),
        ],
      ),
    );
  }

  Widget _form(AppLocalizations l10n, LoginPalette palette) {
    final theme = Theme.of(context);
    final waiting = _secondsLeft > 0;

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 16,
      children: [
        Text(
          l10n.resetTitle,
          style: theme.textTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        Text(l10n.resetIntro, style: theme.textTheme.bodyMedium),
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
        // Every message is laid out, only the current one is shown: the slot
        // is as tall as the longest message, so nothing moves when one
        // appears.
        Stack(
          children: [
            for (final notice in _Notice.values)
              Visibility(
                visible: notice == _notice,
                maintainState: true,
                maintainAnimation: true,
                maintainSize: true,
                child: _noticeRow(l10n, palette, notice),
              ),
          ],
        ),
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
    );
  }

  Widget _noticeRow(
    AppLocalizations l10n,
    LoginPalette palette,
    _Notice notice,
  ) {
    final success = notice == _Notice.sent;
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
            switch (notice) {
              _Notice.sent => l10n.resetSent,
              _Notice.invalidEmail => l10n.resetErrorInvalidEmail,
              _Notice.tooManyAttempts => l10n.resetErrorTooManyAttempts,
              _Notice.network => l10n.resetErrorNetwork,
              _Notice.unknown => l10n.resetErrorUnknown,
            },
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
