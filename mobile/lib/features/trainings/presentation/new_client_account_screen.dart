import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Lets the trainer create a client account for a person who is not in the
/// studio yet. The person gets an e-mail to set a password and becomes the
/// trainer's trainee at once.
class NewClientAccountScreen extends ConsumerStatefulWidget {
  const NewClientAccountScreen({super.key});

  @override
  ConsumerState<NewClientAccountScreen> createState() =>
      _NewClientAccountScreenState();
}

class _NewClientAccountScreenState
    extends ConsumerState<NewClientAccountScreen> {
  static final _emailPattern = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

  final _formKey = GlobalKey<FormState>();
  final _firstNameController = TextEditingController();
  final _lastNameController = TextEditingController();
  final _emailController = TextEditingController();
  bool _saving = false;
  Object? _saveError;

  @override
  void dispose() {
    _firstNameController.dispose();
    _lastNameController.dispose();
    _emailController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving || !_formKey.currentState!.validate()) {
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _saving = true;
      _saveError = null;
    });
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final created = await ref
          .read(traineesApiProvider)
          .createClientAccount(
            email: _emailController.text,
            firstName: _firstNameController.text,
            lastName: _lastNameController.text,
          );
      if (!mounted) {
        return;
      }
      ref.invalidate(ownTraineesProvider);
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            l10n.newClientAccountCreated(created.displayName, created.email),
          ),
        ),
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
    final theme = Theme.of(context);
    final error = _saveError;
    final emailTaken = error is ApiException && error.statusCode == 409;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.newClientAccountTitle)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 400),
              child: Form(
                key: _formKey,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 16,
                  children: [
                    Card(
                      margin: EdgeInsets.zero,
                      color: theme.colorScheme.secondaryContainer,
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          spacing: 12,
                          children: [
                            Icon(
                              Icons.mark_email_unread_outlined,
                              color: theme.colorScheme.onSecondaryContainer,
                            ),
                            Expanded(
                              child: Text(
                                l10n.newClientAccountInfo,
                                style: theme.textTheme.bodyMedium?.copyWith(
                                  color: theme.colorScheme.onSecondaryContainer,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    TextFormField(
                      key: const Key('newClientFirstName'),
                      controller: _firstNameController,
                      enabled: !_saving,
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: InputDecoration(
                        labelText: l10n.newClientAccountFirstName,
                        prefixIcon: const Icon(Icons.person_outline),
                        border: const OutlineInputBorder(),
                        errorMaxLines: 3,
                      ),
                      validator: (value) => _validateName(value, l10n),
                    ),
                    TextFormField(
                      key: const Key('newClientLastName'),
                      controller: _lastNameController,
                      enabled: !_saving,
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: InputDecoration(
                        labelText: l10n.newClientAccountLastName,
                        prefixIcon: const Icon(Icons.person_outline),
                        border: const OutlineInputBorder(),
                        errorMaxLines: 3,
                      ),
                      validator: (value) => _validateName(value, l10n),
                    ),
                    TextFormField(
                      key: const Key('newClientEmail'),
                      controller: _emailController,
                      enabled: !_saving,
                      keyboardType: TextInputType.emailAddress,
                      autocorrect: false,
                      textInputAction: TextInputAction.done,
                      onFieldSubmitted: (_) => _save(),
                      decoration: InputDecoration(
                        labelText: l10n.newClientAccountEmail,
                        helperText: l10n.newClientAccountEmailHint,
                        helperMaxLines: 3,
                        prefixIcon: const Icon(Icons.alternate_email),
                        border: const OutlineInputBorder(),
                        errorMaxLines: 3,
                      ),
                      validator: (value) {
                        final email = value?.trim() ?? '';
                        if (email.isEmpty) {
                          return l10n.newClientAccountRequired;
                        }
                        if (email.length > 254 ||
                            !_emailPattern.hasMatch(email)) {
                          return l10n.newClientAccountEmailInvalid;
                        }
                        return null;
                      },
                    ),
                    if (error != null)
                      Text(
                        _errorMessage(error, l10n),
                        style: TextStyle(color: theme.colorScheme.error),
                      ),
                    if (emailTaken)
                      OutlinedButton.icon(
                        onPressed: () =>
                            context.pushReplacement(newCooperationLocation),
                        icon: const Icon(Icons.search),
                        label: Text(l10n.newClientAccountFindExisting),
                      ),
                    FilledButton.icon(
                      onPressed: _saving ? null : _save,
                      icon: _saving
                          ? SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: theme.colorScheme.onPrimary,
                              ),
                            )
                          : const Icon(Icons.send_outlined),
                      label: Text(l10n.newClientAccountSubmit),
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
      return l10n.newClientAccountRequired;
    }
    if (name.length > 80) {
      return l10n.newClientAccountNameTooLong;
    }
    return null;
  }

  static String _errorMessage(Object error, AppLocalizations l10n) {
    if (error is ApiException) {
      return switch (error.statusCode) {
        null => l10n.traineesNetworkError,
        401 || 403 => l10n.traineesAccessError,
        404 => l10n.traineesNoStaffProfile,
        409 => l10n.newClientAccountEmailTaken,
        422 => l10n.newClientAccountTrainerInactive,
        502 => l10n.newClientAccountEmailNotSent,
        _ => l10n.traineesSaveError,
      };
    }
    return l10n.traineesSaveError;
  }
}
