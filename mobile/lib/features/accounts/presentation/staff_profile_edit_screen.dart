import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/accounts/data/staff_profile.dart';
import 'package:mobile/features/accounts/data/staff_profile_provider.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class StaffProfileEditScreen extends ConsumerStatefulWidget {
  const StaffProfileEditScreen({super.key});

  @override
  ConsumerState<StaffProfileEditScreen> createState() =>
      _StaffProfileEditScreenState();
}

class _StaffProfileEditScreenState
    extends ConsumerState<StaffProfileEditScreen> {
  final _formKey = GlobalKey<FormState>();
  final _firstNameController = TextEditingController();
  final _lastNameController = TextEditingController();
  final _phoneController = TextEditingController();
  String? _loadedAccountId;
  bool _saving = false;
  Object? _saveError;

  @override
  void dispose() {
    _firstNameController.dispose();
    _lastNameController.dispose();
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _save(String accountId) async {
    if (_saving || !_formKey.currentState!.validate()) {
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      await ref
          .read(staffProfileProvider(accountId).notifier)
          .save(
            firstName: _firstNameController.text,
            lastName: _lastNameController.text,
            phone: _phoneController.text,
          );
      if (!mounted || ModalRoute.of(context)?.isCurrent != true) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppLocalizations.of(context).staffProfileSaved)),
      );
      context.pop();
    } catch (e) {
      if (mounted) {
        setState(() => _saveError = e);
      }
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.staffProfileEditTitle)),
      body: switch (auth) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) =>
          ref
              .watch(staffProfileProvider(session.accountId))
              .when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (error, _) => _loadError(session.accountId, error, l10n),
                data: (profile) => _form(session.accountId, profile, l10n),
              ),
      },
    );
  }

  Widget _loadError(String accountId, Object error, AppLocalizations l10n) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            const Icon(Icons.error_outline),
            Text(_errorMessage(error, l10n, loading: true)),
            OutlinedButton.icon(
              onPressed: () => ref.invalidate(staffProfileProvider(accountId)),
              icon: const Icon(Icons.refresh),
              label: Text(l10n.staffProfileRetry),
            ),
          ],
        ),
      ),
    );
  }

  Widget _form(String accountId, StaffProfile profile, AppLocalizations l10n) {
    if (_loadedAccountId != accountId) {
      _loadedAccountId = accountId;
      _firstNameController.text = profile.firstName;
      _lastNameController.text = profile.lastName;
      _phoneController.text = profile.phone ?? '';
    }

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 400),
            child: AutofillGroup(
              child: Form(
                key: _formKey,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 16,
                  children: [
                    TextFormField(
                      controller: _firstNameController,
                      enabled: !_saving,
                      autofillHints: const [AutofillHints.givenName],
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: InputDecoration(
                        labelText: l10n.staffProfileFirstName,
                        prefixIcon: const Icon(Icons.person_outline),
                        border: const OutlineInputBorder(),
                        errorMaxLines: 3,
                      ),
                      validator: (value) => _validateName(value, l10n),
                    ),
                    TextFormField(
                      controller: _lastNameController,
                      enabled: !_saving,
                      autofillHints: const [AutofillHints.familyName],
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: InputDecoration(
                        labelText: l10n.staffProfileLastName,
                        prefixIcon: const Icon(Icons.person_outline),
                        border: const OutlineInputBorder(),
                        errorMaxLines: 3,
                      ),
                      validator: (value) => _validateName(value, l10n),
                    ),
                    TextFormField(
                      controller: _phoneController,
                      enabled: !_saving,
                      autofillHints: const [AutofillHints.telephoneNumber],
                      keyboardType: TextInputType.phone,
                      textInputAction: TextInputAction.done,
                      onFieldSubmitted: (_) => _save(accountId),
                      decoration: InputDecoration(
                        labelText: l10n.staffProfilePhone,
                        helperText: l10n.staffProfilePhoneHint,
                        helperMaxLines: 3,
                        prefixIcon: const Icon(Icons.phone_outlined),
                        border: const OutlineInputBorder(),
                        errorMaxLines: 3,
                      ),
                      validator: (value) => (value?.trim().length ?? 0) > 20
                          ? l10n.staffProfilePhoneTooLong
                          : null,
                    ),
                    if (_saveError case final error?)
                      Text(
                        _errorMessage(error, l10n),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    FilledButton(
                      onPressed: _saving ? null : () => _save(accountId),
                      child: _saving
                          ? SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Theme.of(context).colorScheme.onPrimary,
                              ),
                            )
                          : Text(l10n.staffProfileSave),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  static String? _validateName(String? value, AppLocalizations l10n) {
    final name = value?.trim() ?? '';
    if (name.isEmpty) {
      return l10n.staffProfileNameRequired;
    }
    return name.length > 80 ? l10n.staffProfileNameTooLong : null;
  }

  static String _errorMessage(
    Object error,
    AppLocalizations l10n, {
    bool loading = false,
  }) {
    if (error is ApiException) {
      return switch (error.statusCode) {
        null => l10n.staffProfileNetworkError,
        401 || 403 => l10n.staffProfileAccessError,
        404 => l10n.staffProfileNotFound,
        400 => l10n.staffProfileValidationError,
        _ => loading ? l10n.staffProfileLoadError : l10n.staffProfileSaveError,
      };
    }
    return loading ? l10n.staffProfileLoadError : l10n.staffProfileSaveError;
  }
}
