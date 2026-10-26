import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/accounts/data/customer_profile.dart';
import 'package:mobile/features/accounts/data/customer_profile_provider.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Edits the active customer profile: the client's own or a child's.
class CustomerProfileEditScreen extends ConsumerStatefulWidget {
  const CustomerProfileEditScreen({super.key});

  @override
  ConsumerState<CustomerProfileEditScreen> createState() =>
      _CustomerProfileEditScreenState();
}

class _CustomerProfileEditScreenState
    extends ConsumerState<CustomerProfileEditScreen> {
  static final _phonePattern = RegExp(r'^\+?[0-9][0-9 ()-]*[0-9]$');

  final _formKey = GlobalKey<FormState>();
  final _firstNameController = TextEditingController();
  final _lastNameController = TextEditingController();
  final _birthDateController = TextEditingController();
  final _phoneController = TextEditingController();
  final _emergencyNameController = TextEditingController();
  final _emergencyPhoneController = TextEditingController();
  CustomerProfileKey? _loadedKey;
  DateTime? _birthDate;
  bool _saving = false;
  Object? _saveError;

  @override
  void dispose() {
    _firstNameController.dispose();
    _lastNameController.dispose();
    _birthDateController.dispose();
    _phoneController.dispose();
    _emergencyNameController.dispose();
    _emergencyPhoneController.dispose();
    super.dispose();
  }

  Future<void> _save(CustomerProfileKey key) async {
    if (_saving || !_formKey.currentState!.validate()) {
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _saving = true;
      _saveError = null;
    });
    final auth = ref.read(authControllerProvider.notifier);
    try {
      final profile = await ref
          .read(customerProfileProvider(key).notifier)
          .save(
            firstName: _firstNameController.text,
            lastName: _lastNameController.text,
            birthDate: _birthDate,
            phone: _phoneController.text,
            emergencyContactName: _emergencyNameController.text,
            emergencyPhone: _emergencyPhoneController.text,
          );
      // Renaming changes the session, which refreshes the router, so it runs
      // only after this screen has left the navigation stack.
      if (!mounted || ModalRoute.of(context)?.isCurrent != true) {
        auth.renameProfile(profile.id, profile.displayName);
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppLocalizations.of(context).customerProfileSaved),
        ),
      );
      context.pop();
      auth.renameProfile(profile.id, profile.displayName);
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

  Future<void> _pickBirthDate() async {
    final today = DateUtils.dateOnly(DateTime.now());
    final picked = await showDatePicker(
      context: context,
      initialDate: _birthDate ?? DateTime(today.year - 30),
      firstDate: DateTime(today.year - 120),
      lastDate: today.subtract(const Duration(days: 1)),
      initialEntryMode: DatePickerEntryMode.calendarOnly,
    );
    if (picked != null && mounted) {
      _setBirthDate(picked);
    }
  }

  void _setBirthDate(DateTime? date) {
    setState(() {
      _birthDate = date;
      _birthDateController.text = date == null
          ? ''
          : MaterialLocalizations.of(context).formatCompactDate(date);
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.customerProfileEditTitle)),
      body: switch (auth) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => switch (session.activeProfileId) {
          null => Center(child: Text(l10n.customerProfileNotFound)),
          final profileId => _body((
            accountId: session.accountId,
            profileId: profileId,
          ), l10n),
        },
      },
    );
  }

  Widget _body(CustomerProfileKey key, AppLocalizations l10n) {
    return ref
        .watch(customerProfileProvider(key))
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _loadError(key, error, l10n),
          data: (profile) => _form(key, profile, l10n),
        );
  }

  Widget _loadError(
    CustomerProfileKey key,
    Object error,
    AppLocalizations l10n,
  ) {
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
              onPressed: () => ref.invalidate(customerProfileProvider(key)),
              icon: const Icon(Icons.refresh),
              label: Text(l10n.customerProfileRetry),
            ),
          ],
        ),
      ),
    );
  }

  Widget _form(
    CustomerProfileKey key,
    CustomerProfile profile,
    AppLocalizations l10n,
  ) {
    if (_loadedKey != key) {
      _loadedKey = key;
      _firstNameController.text = profile.firstName;
      _lastNameController.text = profile.lastName;
      _phoneController.text = profile.phone ?? '';
      _emergencyNameController.text = profile.emergencyContactName ?? '';
      _emergencyPhoneController.text = profile.emergencyPhone ?? '';
      _birthDate = profile.birthDate;
      _birthDateController.text = profile.birthDate == null
          ? ''
          : MaterialLocalizations.of(
              context,
            ).formatCompactDate(profile.birthDate!);
    }
    final sectionStyle = Theme.of(context).textTheme.titleMedium;

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
                    if (profile.isChild)
                      Text(
                        l10n.customerProfileChildNotice(profile.displayName),
                      ),
                    TextFormField(
                      controller: _firstNameController,
                      enabled: !_saving,
                      autofillHints: profile.isChild
                          ? null
                          : const [AutofillHints.givenName],
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: _decoration(
                        l10n.customerProfileFirstName,
                        Icons.person_outline,
                      ),
                      validator: (value) => _validateName(value, l10n),
                    ),
                    TextFormField(
                      controller: _lastNameController,
                      enabled: !_saving,
                      autofillHints: profile.isChild
                          ? null
                          : const [AutofillHints.familyName],
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: _decoration(
                        l10n.customerProfileLastName,
                        Icons.person_outline,
                      ),
                      validator: (value) => _validateName(value, l10n),
                    ),
                    TextFormField(
                      key: const Key('birthDateField'),
                      controller: _birthDateController,
                      enabled: !_saving,
                      readOnly: true,
                      onTap: _pickBirthDate,
                      decoration:
                          _decoration(
                            l10n.customerProfileBirthDate,
                            Icons.cake_outlined,
                          ).copyWith(
                            suffixIcon: _birthDate == null || _saving
                                ? null
                                : IconButton(
                                    tooltip: l10n.customerProfileClearBirthDate,
                                    icon: const Icon(Icons.clear),
                                    onPressed: () => _setBirthDate(null),
                                  ),
                          ),
                    ),
                    TextFormField(
                      controller: _phoneController,
                      enabled: !_saving,
                      autofillHints: profile.isChild
                          ? null
                          : const [AutofillHints.telephoneNumber],
                      keyboardType: TextInputType.phone,
                      textInputAction: TextInputAction.next,
                      decoration: _decoration(
                        l10n.customerProfilePhone,
                        Icons.phone_outlined,
                      ),
                      validator: (value) => _validatePhone(value, l10n),
                    ),
                    Text(
                      l10n.customerProfileEmergencySection,
                      style: sectionStyle,
                    ),
                    Text(l10n.customerProfileEmergencyHint),
                    TextFormField(
                      controller: _emergencyNameController,
                      enabled: !_saving,
                      textCapitalization: TextCapitalization.words,
                      textInputAction: TextInputAction.next,
                      decoration: _decoration(
                        l10n.customerProfileEmergencyName,
                        Icons.contact_emergency_outlined,
                      ),
                      validator: (value) {
                        final name = value?.trim() ?? '';
                        if (name.length > 120) {
                          return l10n.customerProfileEmergencyNameTooLong;
                        }
                        return name.isEmpty &&
                                _emergencyPhoneController.text.trim().isNotEmpty
                            ? l10n.customerProfileEmergencyNameRequired
                            : null;
                      },
                    ),
                    TextFormField(
                      controller: _emergencyPhoneController,
                      enabled: !_saving,
                      keyboardType: TextInputType.phone,
                      textInputAction: TextInputAction.done,
                      onFieldSubmitted: (_) => _save(key),
                      decoration: _decoration(
                        l10n.customerProfileEmergencyPhone,
                        Icons.phone_outlined,
                      ),
                      validator: (value) {
                        final phone = value?.trim() ?? '';
                        if (phone.isEmpty &&
                            _emergencyNameController.text.trim().isNotEmpty) {
                          return l10n.customerProfileEmergencyPhoneRequired;
                        }
                        return _validatePhone(value, l10n);
                      },
                    ),
                    if (_saveError case final error?)
                      Text(
                        _errorMessage(error, l10n),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    FilledButton(
                      onPressed: _saving ? null : () => _save(key),
                      child: _saving
                          ? SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Theme.of(context).colorScheme.onPrimary,
                              ),
                            )
                          : Text(l10n.customerProfileSave),
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

  static InputDecoration _decoration(String label, IconData icon) {
    return InputDecoration(
      labelText: label,
      prefixIcon: Icon(icon),
      border: const OutlineInputBorder(),
      errorMaxLines: 3,
    );
  }

  static String? _validateName(String? value, AppLocalizations l10n) {
    final name = value?.trim() ?? '';
    if (name.isEmpty) {
      return l10n.customerProfileNameRequired;
    }
    return name.length > 80 ? l10n.customerProfileNameTooLong : null;
  }

  static String? _validatePhone(String? value, AppLocalizations l10n) {
    final phone = value?.trim() ?? '';
    if (phone.isEmpty) {
      return null;
    }
    if (phone.length > 20) {
      return l10n.customerProfilePhoneTooLong;
    }
    return _phonePattern.hasMatch(phone)
        ? null
        : l10n.customerProfilePhoneInvalid;
  }

  static String _errorMessage(
    Object error,
    AppLocalizations l10n, {
    bool loading = false,
  }) {
    if (error is ApiException) {
      return switch (error.statusCode) {
        null => l10n.customerProfileNetworkError,
        401 || 403 => l10n.customerProfileAccessError,
        404 => l10n.customerProfileNotFound,
        400 => l10n.customerProfileValidationError,
        _ =>
          loading
              ? l10n.customerProfileLoadError
              : l10n.customerProfileSaveError,
      };
    }
    return loading
        ? l10n.customerProfileLoadError
        : l10n.customerProfileSaveError;
  }
}
