import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/customer_profile_api.dart';
import 'package:mobile/features/accounts/data/customer_profile_provider.dart';
import 'package:mobile/features/accounts/data/staff_profile.dart';
import 'package:mobile/features/accounts/data/staff_profile_provider.dart';
import 'package:mobile/features/accounts/presentation/widgets/personal_details_form.dart';
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
    final ownProfile = _ownCustomerProfile();
    final copy = await _confirmClientUpdate(accountId, ownProfile);
    if (copy == null || !mounted) {
      return;
    }
    setState(() {
      _saving = true;
      _saveError = null;
    });
    final auth = ref.read(authControllerProvider.notifier);
    try {
      final profile = await ref
          .read(staffProfileProvider(accountId).notifier)
          .save(
            firstName: _firstNameController.text,
            lastName: _lastNameController.text,
            phone: _phoneController.text,
          );
      String? renamedProfileId;
      String? renamedTo;
      if (copy.firstName || copy.lastName || copy.phone) {
        final customerKey = (accountId: accountId, profileId: ownProfile!.id);
        try {
          final client = await ref
              .read(customerProfileApiProvider)
              .updateSharedDetails(
                ownProfile.id,
                firstName: copy.firstName ? profile.firstName : null,
                lastName: copy.lastName ? profile.lastName : null,
                phone: copy.phone ? profile.phone ?? '' : null,
              );
          renamedProfileId = ownProfile.id;
          renamedTo = client.displayName;
        } catch (e) {
          throw OtherProfileNotUpdated(e);
        } finally {
          if (mounted) {
            ref.invalidate(customerProfileProvider(customerKey));
          }
        }
      }
      void rename() {
        if (renamedProfileId != null && renamedTo != null) {
          auth.renameProfile(renamedProfileId, renamedTo);
        }
      }

      // Renaming changes the session, which refreshes the router, so it runs
      // only after this screen has left the navigation stack.
      if (!mounted || ModalRoute.of(context)?.isCurrent != true) {
        rename();
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppLocalizations.of(context).personalDetailsSaved),
        ),
      );
      context.pop();
      rename();
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

  /// The account's own customer profile, if it has one.
  Profile? _ownCustomerProfile() {
    return switch (ref.read(authControllerProvider)) {
      Authenticated(:final session) =>
        session.profiles.where((p) => !p.isChild).firstOrNull,
      _ => null,
    };
  }

  /// Which changed details also go to the trainer's own customer profile;
  /// `null` cancels the save.
  Future<SharedDetails?> _confirmClientUpdate(
    String accountId,
    Profile? ownProfile,
  ) async {
    const nothing = (firstName: false, lastName: false, phone: false);
    if (ownProfile == null) {
      return nothing;
    }
    final loaded = ref.read(staffProfileProvider(accountId)).requireValue;
    final changed = changedSharedDetails(
      firstName: loaded.firstName,
      lastName: loaded.lastName,
      phone: loaded.phone,
      newFirstName: _firstNameController.text,
      newLastName: _lastNameController.text,
      newPhone: _phoneController.text,
    );
    if (changed == nothing) {
      return nothing;
    }
    return confirmOtherProfileUpdate(
      context,
      changed: changed,
      otherIsTrainer: false,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final auth = ref.watch(authControllerProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.personalDetailsEditTitle)),
      body: switch (auth) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) =>
          ref
              .watch(staffProfileProvider(session.accountId))
              .when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (error, _) => PersonalDetailsLoadError(
                  error: error,
                  onRetry: () =>
                      ref.invalidate(staffProfileProvider(session.accountId)),
                ),
                data: (profile) => _form(session.accountId, profile, l10n),
              ),
      },
    );
  }

  Widget _form(String accountId, StaffProfile profile, AppLocalizations l10n) {
    if (_loadedAccountId != accountId) {
      _loadedAccountId = accountId;
      _firstNameController.text = profile.firstName;
      _lastNameController.text = profile.lastName;
      _phoneController.text = profile.phone ?? '';
    }

    return PersonalDetailsFormLayout(
      formKey: _formKey,
      children: [
        TextFormField(
          controller: _firstNameController,
          enabled: !_saving,
          autofillHints: const [AutofillHints.givenName],
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
          autofillHints: const [AutofillHints.familyName],
          textCapitalization: TextCapitalization.words,
          textInputAction: TextInputAction.next,
          decoration: personalDetailsDecoration(
            label: l10n.personalDetailsLastName,
            icon: Icons.person_outline,
          ),
          validator: (value) => validatePersonName(value, l10n),
        ),
        TextFormField(
          controller: _phoneController,
          enabled: !_saving,
          autofillHints: const [AutofillHints.telephoneNumber],
          keyboardType: TextInputType.phone,
          textInputAction: TextInputAction.done,
          onFieldSubmitted: (_) => _save(accountId),
          decoration: personalDetailsDecoration(
            label: l10n.personalDetailsPhone,
            icon: Icons.phone_outlined,
            helperText: l10n.staffProfilePhoneHint,
          ),
          validator: (value) => validatePhone(value, l10n),
        ),
        if (_saveError case final error?)
          PersonalDetailsSaveError(error: error),
        PersonalDetailsSaveButton(
          saving: _saving,
          onPressed: () => _save(accountId),
        ),
      ],
    );
  }
}
