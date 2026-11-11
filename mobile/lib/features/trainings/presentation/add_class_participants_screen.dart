import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/class_booking_api.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/features/trainings/presentation/trainees_tab.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Lets the trainer find active clients and book them, one after another, on
/// a group class they run or assist, e.g. people who asked to join outside
/// the app's own booking.
class AddClassParticipantsScreen extends ConsumerStatefulWidget {
  const AddClassParticipantsScreen({required this.query, super.key});

  /// The trainer's account and the class.
  final EventParticipantsQuery query;

  /// How long typing must pause before the search is sent.
  static const searchDelay = Duration(milliseconds: 300);

  @override
  ConsumerState<AddClassParticipantsScreen> createState() =>
      _AddClassParticipantsScreenState();
}

class _AddClassParticipantsScreenState
    extends ConsumerState<AddClassParticipantsScreen> {
  final _searchController = TextEditingController();
  Timer? _debounce;
  String _query = '';

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onQueryChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(AddClassParticipantsScreen.searchDelay, () {
      if (mounted) {
        setState(() => _query = value.trim());
      }
    });
  }

  Future<void> _open(CustomerSummary customer) async {
    final booked = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _BookingSheet(
        query: (
          accountId: widget.query.accountId,
          eventId: widget.query.eventId,
          customerId: customer.id,
        ),
        customer: customer,
      ),
    );
    if (booked != true || !mounted) return;
    // The class's participants, and so the occupancy, change.
    ref.invalidate(eventAttendanceProvider(widget.query));
    ref.invalidate(ownScheduleEventProvider(widget.query));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          AppLocalizations.of(context).classBookingBooked(customer.displayName),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final bookedIds = {
      for (final participant
          in ref
                  .watch(eventAttendanceProvider(widget.query))
                  .value
                  ?.participants ??
              const <EventParticipant>[])
        participant.customerId,
    };
    final provider = customerSearchProvider(_query);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.classBookingTitle)),
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
                      ? Padding(
                          padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
                          child: Text(
                            l10n.newCooperationNoResults,
                            textAlign: TextAlign.center,
                          ),
                        )
                      : ListView(
                          children: [
                            for (final customer in customers)
                              _customerTile(
                                customer,
                                booked: bookedIds.contains(customer.id),
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
    required bool booked,
    required AppLocalizations l10n,
  }) {
    final birthDate = customer.birthDate;
    final details = [
      if (customer.isChild) l10n.traineesChild,
      if (birthDate != null) l10n.traineesBirthDate(formatBirthDate(birthDate)),
      if (booked) l10n.classBookingAlreadyBooked,
    ];
    return ListTile(
      title: Text(customer.displayName),
      subtitle: details.isEmpty ? null : Text(details.join(' · ')),
      enabled: !booked,
      trailing: Icon(booked ? Icons.check : Icons.add),
      onTap: () => _open(customer),
    );
  }
}

/// Whether the client can be booked, on the rules of their own booking, and
/// the pass to charge. Pops `true` once the client is booked.
class _BookingSheet extends ConsumerStatefulWidget {
  const _BookingSheet({required this.query, required this.customer});

  final BookingOptionsQuery query;
  final CustomerSummary customer;

  @override
  ConsumerState<_BookingSheet> createState() => _BookingSheetState();
}

class _BookingSheetState extends ConsumerState<_BookingSheet> {
  final _reasonController = TextEditingController();
  String? _passId;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reasonController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = bookingOptionsProvider(widget.query);
    return Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        0,
        24,
        24 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            Text(
              widget.customer.displayName,
              style: theme.textTheme.titleLarge,
            ),
            ...ref
                .watch(provider)
                .when(
                  skipLoadingOnRefresh: false,
                  loading: () => [
                    Row(
                      spacing: 12,
                      children: [
                        const SizedBox.square(
                          dimension: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                        Text(l10n.classBookingChecking),
                      ],
                    ),
                  ],
                  error: (_, _) => [
                    Text(l10n.classBookingOptionsError),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton.icon(
                        onPressed: () => ref.invalidate(provider),
                        icon: const Icon(Icons.refresh),
                        label: Text(l10n.scheduleRetryButton),
                      ),
                    ),
                  ],
                  data: (options) => _options(context, options),
                ),
          ],
        ),
      ),
    );
  }

  List<Widget> _options(BuildContext context, BookingOptions options) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final passes = options.eligiblePasses;
    if (!options.allowed || passes.isEmpty) {
      return [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 12,
          children: [
            Icon(Icons.block, color: theme.colorScheme.error),
            Expanded(child: Text(blockedReason(l10n, options.reason))),
          ],
        ),
      ];
    }
    // A single eligible pass is chosen for the trainer.
    final passId = _passId ?? (passes.length == 1 ? passes.single.id : null);
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    return [
      if (switch (options.waitlistStatus) {
            'WAITING' => l10n.classBookingWaiting,
            'OFFERED' => l10n.classBookingOffered,
            _ => null,
          }
          case final note?)
        Text(note),
      Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(l10n.classBookingPass, style: theme.textTheme.titleSmall),
          RadioGroup<String>(
            groupValue: passId,
            onChanged: (value) {
              if (!_saving) setState(() => _passId = value);
            },
            child: Column(
              children: [
                for (final pass in passes)
                  RadioListTile<String>(
                    contentPadding: EdgeInsets.zero,
                    value: pass.id,
                    title: Text(_passLabel(l10n, pass)),
                    subtitle: pass.validUntil == null
                        ? null
                        : Text(
                            l10n.classBookingPassValidUntil(
                              formatBirthDate(pass.validUntil!),
                            ),
                          ),
                  ),
              ],
            ),
          ),
        ],
      ),
      TextField(
        controller: _reasonController,
        enabled: !_saving,
        maxLength: 500,
        minLines: 1,
        maxLines: 3,
        decoration: InputDecoration(
          labelText: l10n.classBookingReason,
          hintText: l10n.classBookingReasonHint,
          border: const OutlineInputBorder(),
        ),
      ),
      Text(l10n.classBookingHistoryNote, style: muted),
      // A rejection that leaves the client bookable, e.g. a pass that changed;
      // otherwise the reloaded options say why the client cannot be booked.
      if (_error case final error?)
        Text(error, style: TextStyle(color: theme.colorScheme.error)),
      FilledButton.icon(
        onPressed: !_saving && passId != null ? () => _book(passId) : null,
        icon: _saving
            ? const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.person_add_alt_1),
        label: Text(l10n.classBookingSubmit),
      ),
    ];
  }

  Future<void> _book(String passId) async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(classBookingApiProvider)
          .book(
            widget.query.eventId,
            customerId: widget.query.customerId,
            passId: passId,
            reason: _reasonController.text,
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      final l10n = AppLocalizations.of(context);
      final code = error is ClassBookingException ? error.code : null;
      setState(() {
        _saving = false;
        _error = switch (error) {
          ClassBookingException(
            code: 'PASS_NOT_USABLE' ||
                'PASS_ENTRIES_EXHAUSTED' ||
                'PASS_SERVICE_MISMATCH',
          ) =>
            l10n.classBookingErrorPass,
          ClassBookingException(statusCode: 404) =>
            l10n.classBookingErrorNotFound,
          ClassBookingException(statusCode: null) =>
            l10n.classBookingErrorNetwork,
          // A rule the booking breaks now, e.g. the class filled up
          // meanwhile.
          ClassBookingException(statusCode: 409 || 422) => blockedReason(
            l10n,
            code,
          ),
          _ => l10n.classBookingErrorUnknown,
        };
        if (code != null) {
          // The conditions changed meanwhile: show the current ones.
          _passId = null;
          ref.invalidate(bookingOptionsProvider(widget.query));
        }
      });
    }
  }

  static String _passLabel(AppLocalizations l10n, EligiblePass pass) =>
      switch (pass.entriesRemaining) {
        final entries? => l10n.classBookingPassLabel(pass.productName, entries),
        null => pass.productName,
      };
}

/// Why the client cannot be booked on the class, by the backend's reason
/// code; the same rules apply as to the client's own booking.
String blockedReason(AppLocalizations l10n, String? reason) => switch (reason) {
  'ALREADY_RESERVED' => l10n.classBookingBlockedReserved,
  'CUSTOMER_NOT_ACTIVE' => l10n.classBookingBlockedInactive,
  'EVENT_NOT_BOOKABLE' => l10n.classBookingBlockedNotBookable,
  'BOOKING_NOT_OPEN' => l10n.classBookingBlockedNotOpen,
  'BOOKING_CLOSED' => l10n.classBookingBlockedClosed,
  'CLASS_FULL' => l10n.classBookingBlockedFull,
  'SERVICE_TYPE_UNAVAILABLE' => l10n.classBookingBlockedServiceUnavailable,
  'AGE_RESTRICTION' => l10n.classBookingBlockedAge,
  'REQUIRED_DOCUMENTS_MISSING' => l10n.classBookingBlockedDocuments,
  'NO_ELIGIBLE_PASS' => l10n.classBookingBlockedNoPass,
  _ => l10n.classBookingBlockedOther,
};
