import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/accounts/data/invitation_api.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_page.dart';
import 'package:mobile/features/accounts/presentation/widgets/login_palette.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Same limits as the backend's accept request.
const invitationPasswordMinLength = 8;
const invitationPasswordMaxLength = 128;

/// What the message slot under the password fields can say.
enum _Notice { tooShort, tooLong, mismatch, network, unknown }

/// Opened from the invitation e-mail link (`/invite?token=…`): the invited
/// client or trainer sets a password and is signed in right away.
class AcceptInvitationScreen extends ConsumerStatefulWidget {
  const AcceptInvitationScreen({super.key, required this.token});

  final String token;

  @override
  ConsumerState<AcceptInvitationScreen> createState() =>
      _AcceptInvitationScreenState();
}

class _AcceptInvitationScreenState
    extends ConsumerState<AcceptInvitationScreen> {
  final _passwordController = TextEditingController();
  final _repeatController = TextEditingController();

  InvitationDetails? _details;
  InvitationFailureReason? _failure;
  bool _submitting = false;

  /// The account is active, but signing in afterwards failed.
  bool _activated = false;
  _Notice? _notice;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _passwordController.dispose();
    _repeatController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _failure = null;
      _details = null;
    });
    if (widget.token.isEmpty) {
      setState(() => _failure = InvitationFailureReason.notFound);
      return;
    }
    try {
      final details = await ref.read(invitationApiProvider).fetch(widget.token);
      if (mounted) {
        setState(() => _details = details);
      }
    } on InvitationFailure catch (e) {
      if (mounted) {
        setState(() => _failure = e.reason);
      }
    }
  }

  Future<void> _activate() async {
    final details = _details!;
    final password = _passwordController.text;
    final notice = password.length < invitationPasswordMinLength
        ? _Notice.tooShort
        : password.length > invitationPasswordMaxLength
        ? _Notice.tooLong
        : password != _repeatController.text
        ? _Notice.mismatch
        : null;
    if (notice != null) {
      setState(() => _notice = notice);
      return;
    }
    setState(() {
      _submitting = true;
      _notice = null;
    });
    try {
      await ref.read(invitationApiProvider).accept(widget.token, password);
    } on InvitationFailure catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _submitting = false;
        switch (e.reason) {
          case InvitationFailureReason.network:
            _notice = _Notice.network;
          case InvitationFailureReason.unknown:
            _notice = _Notice.unknown;
          default:
            _failure = e.reason;
        }
      });
      return;
    }
    try {
      await ref
          .read(authControllerProvider.notifier)
          .login(details.email, password);
      // Signed in: the router has already left this screen.
    } catch (_) {
      if (mounted) {
        setState(() {
          _submitting = false;
          _activated = true;
        });
      }
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
            child: _content(context, l10n, palette),
          ),
        ),
      ),
    );
  }

  Widget _content(
    BuildContext context,
    AppLocalizations l10n,
    LoginPalette palette,
  ) {
    final failure = _failure;
    if (failure != null) {
      return _failureView(context, l10n, palette, failure);
    }
    if (_activated) {
      return _infoView(
        context,
        palette,
        title: l10n.inviteActivatedTitle,
        text: l10n.inviteActivatedText,
        button: l10n.inviteGoToLogin,
        onPressed: () => context.go(loginPath),
      );
    }
    final details = _details;
    if (details == null) {
      return Center(child: CircularProgressIndicator(color: palette.accent));
    }
    return _form(context, l10n, palette, details);
  }

  Widget _form(
    BuildContext context,
    AppLocalizations l10n,
    LoginPalette palette,
    InvitationDetails details,
  ) {
    final firstName = details.displayName.split(' ').first;
    final role = details.roles.contains('TRAINER')
        ? l10n.inviteRoleTrainer
        : l10n.inviteRoleClient;

    // Same rows as the login form (field, field, message, button, link), so the
    // fields and the button stay where they are on the login screen. The
    // heading sits above the first field and grows upwards.
    return AutofillGroup(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: 16,
        children: [
          Stack(
            clipBehavior: Clip.none,
            children: [
              TextField(
                controller: _passwordController,
                obscureText: true,
                autofillHints: const [AutofillHints.newPassword],
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: l10n.loginPasswordLabel,
                  prefixIcon: const Icon(Icons.lock_outline),
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: loginFieldHeight + _headingBottomGap,
                child: _heading(
                  context,
                  l10n.inviteTitle,
                  l10n.inviteIntro(firstName, role, details.email),
                ),
              ),
            ],
          ),
          TextField(
            controller: _repeatController,
            obscureText: true,
            autofillHints: const [AutofillHints.newPassword],
            onSubmitted: (_) => _submitting ? null : _activate(),
            decoration: InputDecoration(
              labelText: l10n.inviteRepeatPasswordLabel,
              prefixIcon: const Icon(Icons.lock_outline),
            ),
          ),
          LoginMessageSlot(
            message: _notice == null
                ? null
                : LoginMessage(
                    palette: palette,
                    text: _noticeText(l10n, _notice!),
                  ),
          ),
          Column(
            children: [
              LoginButton(
                palette: palette,
                onPressed: _submitting ? null : _activate,
                child: _submitting
                    ? SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: palette.onButton,
                        ),
                      )
                    : Text(l10n.inviteActivateButton),
              ),
              LoginLink(
                palette: palette,
                label: l10n.inviteHaveAccount,
                onPressed: () => context.go(loginPath),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _failureView(
    BuildContext context,
    AppLocalizations l10n,
    LoginPalette palette,
    InvitationFailureReason failure,
  ) {
    return switch (failure) {
      InvitationFailureReason.network ||
      InvitationFailureReason.unknown => _infoView(
        context,
        palette,
        title: l10n.inviteLoadFailedTitle,
        text: failure == InvitationFailureReason.network
            ? l10n.loginErrorNetwork
            : l10n.inviteLoadFailedText,
        button: l10n.inviteRetry,
        onPressed: _load,
        error: true,
      ),
      InvitationFailureReason.expired => _infoView(
        context,
        palette,
        title: l10n.inviteExpiredTitle,
        text: l10n.inviteExpiredText,
        button: l10n.inviteGoToLogin,
        onPressed: () => context.go(loginPath),
        error: true,
      ),
      InvitationFailureReason.used => _infoView(
        context,
        palette,
        title: l10n.inviteUsedTitle,
        text: l10n.inviteUsedText,
        button: l10n.inviteGoToLogin,
        onPressed: () => context.go(loginPath),
      ),
      InvitationFailureReason.notFound => _infoView(
        context,
        palette,
        title: l10n.inviteNotFoundTitle,
        text: l10n.inviteNotFoundText,
        button: l10n.inviteGoToLogin,
        onPressed: () => context.go(loginPath),
        error: true,
      ),
    };
  }

  Widget _infoView(
    BuildContext context,
    LoginPalette palette, {
    required String title,
    required String text,
    required String button,
    required VoidCallback onPressed,
    bool error = false,
  }) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 16,
      children: [
        _heading(context, title, null),
        LoginMessage(palette: palette, text: text, success: !error),
        const SizedBox(height: 8),
        LoginButton(
          palette: palette,
          onPressed: onPressed,
          child: Text(button),
        ),
      ],
    );
  }

  Widget _heading(BuildContext context, String title, String? intro) {
    final theme = Theme.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 8,
      children: [
        Text(
          title,
          style: theme.textTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        if (intro != null) Text(intro, style: theme.textTheme.bodyMedium),
      ],
    );
  }

  static String _noticeText(AppLocalizations l10n, _Notice notice) {
    return switch (notice) {
      _Notice.tooShort => l10n.invitePasswordTooShort(
        invitationPasswordMinLength,
      ),
      _Notice.tooLong => l10n.invitePasswordTooLong(
        invitationPasswordMaxLength,
      ),
      _Notice.mismatch => l10n.invitePasswordMismatch,
      _Notice.network => l10n.loginErrorNetwork,
      _Notice.unknown => l10n.inviteActivateFailed,
    };
  }
}

/// Lifts the heading above the first password field.
const _headingBottomGap = 12.0;
