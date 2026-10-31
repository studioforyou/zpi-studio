import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/accounts/data/customer_profile.dart';
import 'package:mobile/features/accounts/data/customer_profile_provider.dart';
import 'package:mobile/features/accounts/presentation/widgets/personal_details_form.dart';
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

  /// The emergency contact's name and phone are only required together, so a
  /// missing half is reported once the client tries to save, not while typing.
  bool _saveAttempted = false;
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
    if (_saving) {
      return;
    }
    setState(() => _saveAttempted = true);
    if (!_formKey.currentState!.validate()) {
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
          content: Text(AppLocalizations.of(context).personalDetailsSaved),
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
      appBar: AppBar(title: Text(l10n.personalDetailsEditTitle)),
      body: switch (auth) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => switch (session.activeProfileId) {
          null => Center(child: Text(l10n.personalDetailsNotFound)),
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
          error: (error, _) => PersonalDetailsLoadError(
            error: error,
            onRetry: () => ref.invalidate(customerProfileProvider(key)),
          ),
          data: (profile) => _form(key, profile, l10n),
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
    // Autofill would offer the guardian's own details for a child.
    final ownProfile = !profile.isChild;

    return PersonalDetailsFormLayout(
      formKey: _formKey,
      children: [
        if (profile.isChild)
          Text(l10n.customerProfileChildNotice(profile.displayName)),
        TextFormField(
          controller: _firstNameController,
          enabled: !_saving,
          autofillHints: ownProfile ? const [AutofillHints.givenName] : null,
          textCapitalization: TextCapitalization.words,
          textInputAction: TextInputAction.next,
          decoration: personalDetailsDecoration(
            label: l10n.personalDetailsFirstName,
            icon: Icons.person_outline,
          ),
          validator: (value) => validatePersonName(value, l10n),
        ),
        TextFormField(
          controller: _lastNameController,
          enabled: !_saving,
          autofillHints: ownProfile ? const [AutofillHints.familyName] : null,
          textCapitalization: TextCapitalization.words,
          textInputAction: TextInputAction.next,
          decoration: personalDetailsDecoration(
            label: l10n.personalDetailsLastName,
            icon: Icons.person_outline,
          ),
          validator: (value) => validatePersonName(value, l10n),
        ),
        TextFormField(
          controller: _birthDateController,
          enabled: !_saving,
          readOnly: true,
          onTap: _pickBirthDate,
          decoration:
              personalDetailsDecoration(
                label: l10n.customerProfileBirthDate,
                icon: Icons.cake_outlined,
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
          autofillHints: ownProfile
              ? const [AutofillHints.telephoneNumber]
              : null,
          keyboardType: TextInputType.phone,
          textInputAction: TextInputAction.next,
          decoration: personalDetailsDecoration(
            label: l10n.personalDetailsPhone,
            icon: Icons.phone_outlined,
          ),
          validator: (value) => validatePhone(value, l10n),
        ),
        Text(
          l10n.customerProfileEmergencySection,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        Text(l10n.customerProfileEmergencyHint),
        TextFormField(
          controller: _emergencyNameController,
          enabled: !_saving,
          textCapitalization: TextCapitalization.words,
          textInputAction: TextInputAction.next,
          decoration: personalDetailsDecoration(
            label: l10n.customerProfileEmergencyName,
            icon: Icons.contact_emergency_outlined,
          ),
          validator: (value) {
            if (_saveAttempted &&
                (value?.trim() ?? '').isEmpty &&
                _emergencyPhoneController.text.trim().isNotEmpty) {
              return l10n.customerProfileEmergencyNameRequired;
            }
            return validatePersonName(
              value,
              l10n,
              maxLength: 120,
              optional: true,
            );
          },
        ),
        TextFormField(
          controller: _emergencyPhoneController,
          enabled: !_saving,
          keyboardType: TextInputType.phone,
          textInputAction: TextInputAction.done,
          onFieldSubmitted: (_) => _save(key),
          decoration: personalDetailsDecoration(
            label: l10n.customerProfileEmergencyPhone,
            icon: Icons.phone_outlined,
          ),
          validator: (value) {
            if (_saveAttempted &&
                (value?.trim() ?? '').isEmpty &&
                _emergencyNameController.text.trim().isNotEmpty) {
              return l10n.customerProfileEmergencyPhoneRequired;
            }
            return validatePhone(value, l10n);
          },
        ),
        if (_saveError case final error?)
          PersonalDetailsSaveError(error: error),
        PersonalDetailsSaveButton(saving: _saving, onPressed: () => _save(key)),
      ],
    );
  }
}
