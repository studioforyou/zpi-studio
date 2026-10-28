import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Lets the trainer find an active participant and start working with them.
class NewCooperationScreen extends ConsumerStatefulWidget {
  const NewCooperationScreen({super.key});

  /// How long typing must pause before the search is sent.
  static const searchDelay = Duration(milliseconds: 300);

  @override
  ConsumerState<NewCooperationScreen> createState() =>
      _NewCooperationScreenState();
}

class _NewCooperationScreenState extends ConsumerState<NewCooperationScreen> {
  final _searchController = TextEditingController();
  Timer? _debounce;
  String _query = '';
  String? _savingCustomerId;

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onQueryChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(NewCooperationScreen.searchDelay, () {
      if (mounted) {
        setState(() => _query = value.trim());
      }
    });
  }

  Future<void> _register(CustomerSummary customer) async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.traineesNewCooperation),
        content: Text(l10n.newCooperationConfirmText(customer.displayName)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l10n.traineesCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(l10n.newCooperationConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() => _savingCustomerId = customer.id);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(traineesApiProvider).assign(customer.id);
      if (!mounted) {
        return;
      }
      ref.invalidate(ownTraineesProvider);
      messenger.showSnackBar(
        SnackBar(
          content: Text(l10n.newCooperationRegistered(customer.displayName)),
        ),
      );
      context.pop();
    } catch (e) {
      messenger.showSnackBar(
        SnackBar(content: Text(traineesErrorMessage(e, l10n))),
      );
    } finally {
      if (mounted) {
        setState(() => _savingCustomerId = null);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final assignedIds = session == null
        ? const <String>{}
        : {
            for (final trainee
                in ref.watch(ownTraineesProvider(session.accountId)).value ??
                    const <Trainee>[])
              trainee.customerId,
          };
    final provider = customerSearchProvider(_query);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.traineesNewCooperation)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _searchController,
              autofocus: true,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                labelText: l10n.newCooperationSearchLabel,
                hintText: l10n.newCooperationSearchHint,
                border: const OutlineInputBorder(),
              ),
              onChanged: _onQueryChanged,
            ),
          ),
          Expanded(
            child: ref
                .watch(provider)
                .when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (error, _) => Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        spacing: 16,
                        children: [
                          Text(
                            traineesErrorMessage(error, l10n, loading: true),
                            textAlign: TextAlign.center,
                          ),
                          OutlinedButton.icon(
                            onPressed: () => ref.invalidate(provider),
                            icon: const Icon(Icons.refresh),
                            label: Text(l10n.traineesRetry),
                          ),
                        ],
                      ),
                    ),
                  ),
                  data: (customers) => customers.isEmpty
                      ? Center(child: Text(l10n.newCooperationNoResults))
                      : ListView(
                          children: [
                            for (final customer in customers)
                              _customerTile(
                                customer,
                                assigned: assignedIds.contains(customer.id),
                                l10n: l10n,
                              ),
                          ],
                        ),
                ),
          ),
        ],
      ),
    );
  }

  Widget _customerTile(
    CustomerSummary customer, {
    required bool assigned,
    required AppLocalizations l10n,
  }) {
    final details = [
      if (customer.isChild) l10n.traineesChild,
      if (assigned) l10n.newCooperationAlreadyAssigned,
    ];
    final saving = _savingCustomerId == customer.id;
    return ListTile(
      title: Text(customer.displayName),
      subtitle: details.isEmpty ? null : Text(details.join(' · ')),
      enabled: !assigned && _savingCustomerId == null,
      trailing: saving
          ? const SizedBox.square(
              dimension: 24,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : Icon(assigned ? Icons.check : Icons.add),
      onTap: () => _register(customer),
    );
  }
}
