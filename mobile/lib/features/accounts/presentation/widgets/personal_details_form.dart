import 'package:flutter/material.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Building blocks shared by the trainer's and the client's personal details
/// editors, so both look and behave the same.

/// The scrollable, width-limited form both editors are laid out in.
class PersonalDetailsFormLayout extends StatelessWidget {
  const PersonalDetailsFormLayout({
    super.key,
    required this.formKey,
    required this.children,
  });

  final GlobalKey<FormState> formKey;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 400),
            child: AutofillGroup(
              child: Form(
                key: formKey,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 16,
                  children: children,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Shown instead of the form when the details could not be loaded.
class PersonalDetailsLoadError extends StatelessWidget {
  const PersonalDetailsLoadError({
    super.key,
    required this.error,
    required this.onRetry,
  });

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            const Icon(Icons.error_outline),
            Text(personalDetailsErrorMessage(error, l10n, loading: true)),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: Text(l10n.personalDetailsRetry),
            ),
          ],
        ),
      ),
    );
  }
}

/// The save button, with a progress indicator while saving.
class PersonalDetailsSaveButton extends StatelessWidget {
  const PersonalDetailsSaveButton({
    super.key,
    required this.saving,
    required this.onPressed,
  });

  final bool saving;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return FilledButton(
      onPressed: saving ? null : onPressed,
      child: saving
          ? SizedBox.square(
              dimension: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Theme.of(context).colorScheme.onPrimary,
              ),
            )
          : Text(AppLocalizations.of(context).personalDetailsSave),
    );
  }
}

/// A save error shown above the save button.
class PersonalDetailsSaveError extends StatelessWidget {
  const PersonalDetailsSaveError({super.key, required this.error});

  final Object error;

  @override
  Widget build(BuildContext context) {
    return Text(
      personalDetailsErrorMessage(error, AppLocalizations.of(context)),
      style: TextStyle(color: Theme.of(context).colorScheme.error),
    );
  }
}

InputDecoration personalDetailsDecoration({
  required String label,
  required IconData icon,
  String? helperText,
}) {
  return InputDecoration(
    labelText: label,
    helperText: helperText,
    helperMaxLines: 3,
    prefixIcon: Icon(icon),
    border: const OutlineInputBorder(),
    errorMaxLines: 3,
  );
}

/// First and last names: required, at most 80 characters.
String? validatePersonName(String? value, AppLocalizations l10n) {
  final name = value?.trim() ?? '';
  if (name.isEmpty) {
    return l10n.personalDetailsNameRequired;
  }
  return name.length > 80 ? l10n.personalDetailsNameTooLong : null;
}

/// Phone numbers: optional, at most 20 characters.
String? validatePhoneLength(String? value, AppLocalizations l10n) {
  return (value?.trim().length ?? 0) > 20
      ? l10n.personalDetailsPhoneTooLong
      : null;
}

String personalDetailsErrorMessage(
  Object error,
  AppLocalizations l10n, {
  bool loading = false,
}) {
  final fallback = loading
      ? l10n.personalDetailsLoadError
      : l10n.personalDetailsSaveError;
  if (error is ApiException) {
    return switch (error.statusCode) {
      null => l10n.personalDetailsNetworkError,
      401 || 403 => l10n.personalDetailsAccessError,
      404 => l10n.personalDetailsNotFound,
      400 => l10n.personalDetailsValidationError,
      _ => fallback,
    };
  }
  return fallback;
}
