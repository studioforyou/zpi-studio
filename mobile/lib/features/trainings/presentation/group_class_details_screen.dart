import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/documents/presentation/documents_screen.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/class_participants_section.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_summary.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class GroupClassDetailsScreen extends ConsumerStatefulWidget {
  const GroupClassDetailsScreen({required this.query, super.key});

  final ClassQuery query;

  @override
  ConsumerState<GroupClassDetailsScreen> createState() =>
      _GroupClassDetailsScreenState();
}

class _GroupClassDetailsScreenState
    extends ConsumerState<GroupClassDetailsScreen> {
  bool _busy = false;
  bool _sending = false;
  String? _actionMessage;
  final _scrollController = ScrollController();

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final activeScope = bookingScope(ref.watch(authControllerProvider));
    final provider = groupClassProvider(widget.query);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.classesDetails)),
      body: activeScope != widget.query.scope
          ? Column(
              children: [
                ClassMessage(l10n.classesProfileChanged),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(l10n.classesBack),
                ),
              ],
            )
          : RefreshIndicator(
              onRefresh: () => ref.refresh(provider.future),
              child: ListView(
                controller: _scrollController,
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                children: [
                  if (_actionMessage case final message?)
                    Card(
                      color: Theme.of(context).colorScheme.errorContainer,
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Text(message),
                      ),
                    ),
                  ...ref
                      .watch(provider)
                      .when(
                        skipLoadingOnRefresh: false,
                        skipLoadingOnReload: false,
                        loading: () => const [
                          Padding(
                            padding: EdgeInsets.all(48),
                            child: Center(child: CircularProgressIndicator()),
                          ),
                        ],
                        error: (_, _) => [
                          ClassesLoadError(
                            onRetry: () => ref.invalidate(provider),
                          ),
                        ],
                        data: _details,
                      ),
                ],
              ),
            ),
    );
  }

  List<Widget> _details(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final participant = _participantName();
    final reservation = event.ownReservation;
    // The studio may book a place without a pass entry; cancelling it costs nothing.
    final withoutPass =
        reservation != null &&
        reservation.customerPassId == null &&
        reservation.makeupCreditId == null;
    final isReserved = event.isReserved;
    final waiting = event.isWaitlisted ? event.waitlist.ownEntry : null;
    final canReserve =
        event.booking.allowed && event.booking.eligiblePasses.isNotEmpty;
    return [
      GroupClassSummary(event: event, participant: participant),
      const SizedBox(height: 16),
      if (isReserved && reservation != null) ...[
        ClassInfoLine(Icons.check_circle_outline, l10n.classesReserved),
        if (reservation.makeupCreditId != null) ...[
          const SizedBox(height: 12),
          ClassInfoLine(Icons.replay, l10n.makeupReservation),
        ],
        if (withoutPass) ...[
          const SizedBox(height: 12),
          Text(l10n.classesReservedWithoutPass),
        ],
        if (reservation.freeCancelUntil case final deadline?
            when reservation.cancellation.allowed &&
                reservation.cancellation.entryReturned == true) ...[
          const SizedBox(height: 12),
          Text(l10n.classesFreeCancelUntil(classDateTime(deadline))),
        ],
        if (!reservation.cancellation.allowed ||
            (!withoutPass &&
                reservation.cancellation.entryReturned != true)) ...[
          const SizedBox(height: 12),
          Text(
            reservation.cancellation.allowed
                ? reservation.makeupCreditId != null
                      ? l10n.makeupCancelForfeits
                      : l10n.classesCancelForfeitsEntry
                : classReason(l10n, reservation.cancellation.reason),
          ),
        ],
        const SizedBox(height: 16),
        OutlinedButton.icon(
          onPressed: _busy || !reservation.cancellation.allowed
              ? null
              : () => _cancel(event, reservation),
          icon: const Icon(Icons.event_busy_outlined),
          label: Text(l10n.classesCancel),
        ),
      ] else if (waiting != null)
        ..._waitlistEntry(event, waiting, canReserve: canReserve)
      else if (event.booking.reason == 'CLASS_FULL' &&
          event.waitlist.reason != 'WAITLIST_DISABLED')
        ..._waitlistOffer(event)
      else ...[
        if (!canReserve) ...[
          Text(classReason(l10n, event.booking.reason ?? 'NO_ELIGIBLE_PASS')),
          if (event.booking.reason == 'REQUIRED_DOCUMENTS_MISSING') ...[
            const SizedBox(height: 8),
            _documentsButton(l10n),
          ],
          const SizedBox(height: 16),
        ],
        FilledButton.icon(
          onPressed: _busy || !canReserve ? null : () => _reserve(event),
          icon: const Icon(Icons.add_task),
          label: Text(l10n.classesReserve),
        ),
      ],
      if (_sending)
        const Padding(
          padding: EdgeInsets.all(12),
          child: Center(child: CircularProgressIndicator()),
        ),
      ClassParticipantsSection(query: widget.query, hasReservation: isReserved),
    ];
  }

  /// The participant waits for a place: their position, whether a freed
  /// place would be given to them now, and a way out of the queue.
  List<Widget> _waitlistEntry(
    GroupClass event,
    ClassWaitlistEntry entry, {
    required bool canReserve,
  }) {
    final l10n = AppLocalizations.of(context);
    if (entry.isOffered) return _waitlistOfferToConfirm(event, entry);
    final blocked = entry.promotionBlockedReason;
    final confirmMinutes = event.waitlist.confirmationMinutes;
    return [
      ClassInfoLine(Icons.hourglass_top_outlined, l10n.classesWaitlistJoined),
      if (entry.position case final position?) ...[
        const SizedBox(height: 12),
        Text(
          l10n.classesWaitlistPosition(
            position,
            // A count read a moment apart never shows "3 of 2".
            math.max(position, event.waitlist.waitingCount),
          ),
        ),
      ],
      const SizedBox(height: 12),
      if (blocked != null) ...[
        ClassInfoLine(Icons.warning_amber_outlined, switch (blocked) {
          'BOOKING_CLOSED' ||
          'EVENT_NOT_BOOKABLE' => l10n.classesWaitlistClosed,
          _ => l10n.classesWaitlistBlocked(classReason(l10n, blocked)),
        }),
        if (blocked == 'REQUIRED_DOCUMENTS_MISSING') ...[
          const SizedBox(height: 8),
          _documentsButton(l10n),
        ],
      ] else
        // Places are given only when a reservation is cancelled. One that is
        // free now was skipped by the queue, so the participant books it.
        Text(
          canReserve
              ? l10n.classesWaitlistPlaceFreed
              : confirmMinutes > 0
              ? l10n.classesWaitlistConfirmNotice(confirmMinutes)
              : l10n.classesWaitlistAutoBooking,
        ),
      const SizedBox(height: 16),
      if (canReserve) ...[
        FilledButton.icon(
          onPressed: _busy ? null : () => _reserve(event),
          icon: const Icon(Icons.add_task),
          label: Text(l10n.classesReserve),
        ),
        const SizedBox(height: 8),
      ],
      OutlinedButton.icon(
        onPressed: _busy ? null : () => _leaveWaitlist(event, entry),
        icon: const Icon(Icons.playlist_remove),
        label: Text(l10n.classesWaitlistLeave),
      ),
    ];
  }

  /// A freed place held for the participant: confirm it before the deadline
  /// or give it to the next one on the list.
  List<Widget> _waitlistOfferToConfirm(
    GroupClass event,
    ClassWaitlistEntry entry,
  ) {
    final l10n = AppLocalizations.of(context);
    final blocked = entry.promotionBlockedReason;
    final deadline = entry.offerExpiresAt;
    final open = deadline == null || DateTime.now().isBefore(deadline);
    return [
      ClassInfoLine(
        Icons.event_available_outlined,
        l10n.classesWaitlistOffered,
      ),
      const SizedBox(height: 12),
      Text(
        open
            ? l10n.classesWaitlistOfferDeadline(
                deadline == null ? '' : classDateTime(deadline),
              )
            : l10n.classesWaitlistOfferExpired,
      ),
      if (blocked != null && open) ...[
        const SizedBox(height: 12),
        ClassInfoLine(
          Icons.warning_amber_outlined,
          l10n.classesWaitlistOfferBlocked(classReason(l10n, blocked)),
        ),
        if (blocked == 'REQUIRED_DOCUMENTS_MISSING') ...[
          const SizedBox(height: 8),
          _documentsButton(l10n),
        ],
      ],
      const SizedBox(height: 16),
      FilledButton.icon(
        onPressed: _busy || !open || blocked != null
            ? null
            : () => _confirmOffer(event, entry),
        icon: const Icon(Icons.check),
        label: Text(l10n.classesWaitlistConfirmOffer),
      ),
      const SizedBox(height: 8),
      OutlinedButton.icon(
        onPressed: _busy || !open ? null : () => _declineOffer(event, entry),
        icon: const Icon(Icons.close),
        label: Text(l10n.classesWaitlistDecline),
      ),
    ];
  }

  /// A full class with a waiting list: an invitation to join it, or why the
  /// participant cannot.
  List<Widget> _waitlistOffer(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final waitlist = event.waitlist;
    final canJoin = waitlist.allowed && event.booking.eligiblePasses.isNotEmpty;
    final reason = waitlist.reason ?? 'NO_ELIGIBLE_PASS';
    return [
      Text(l10n.classesWaitlistNoPlaces),
      const SizedBox(height: 8),
      if (canJoin) ...[
        Text(
          waitlist.confirmationMinutes > 0
              ? l10n.classesWaitlistInviteConfirm(waitlist.confirmationMinutes)
              : l10n.classesWaitlistInvite,
        ),
        if (waitlist.waitingCount > 0) ...[
          const SizedBox(height: 8),
          Text(l10n.classesWaitlistWaitingCount(waitlist.waitingCount)),
        ],
      ] else ...[
        Text(classReason(l10n, reason)),
        if (reason == 'REQUIRED_DOCUMENTS_MISSING') ...[
          const SizedBox(height: 8),
          _documentsButton(l10n),
        ],
      ],
      const SizedBox(height: 16),
      FilledButton.icon(
        onPressed: _busy || !canJoin ? null : () => _joinWaitlist(event),
        icon: const Icon(Icons.playlist_add),
        label: Text(l10n.classesWaitlistJoin),
      ),
    ];
  }

  Widget _documentsButton(AppLocalizations l10n) => OutlinedButton.icon(
    onPressed: () => context.push(documentsRoute),
    icon: const Icon(Icons.description_outlined),
    label: Text(l10n.classesOpenDocuments),
  );

  Future<void> _reserve(GroupClass event) async {
    if (event.booking.eligiblePasses.isEmpty) return;
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () => _choosePass(
        event,
        title: l10n.classesReserveTitle,
        confirm: l10n.classesReserve,
        entryNote: l10n.classesReserveEntry,
      ),
      send: (passId) =>
          ref.read(groupClassesApiProvider).reserve(widget.query, passId),
      success: l10n.classesBookingSuccess,
    );
  }

  Future<void> _joinWaitlist(GroupClass event) async {
    if (event.booking.eligiblePasses.isEmpty) return;
    final l10n = AppLocalizations.of(context);
    final confirmMinutes = event.waitlist.confirmationMinutes;
    await _act(
      ask: () => _choosePass(
        event,
        title: l10n.classesWaitlistJoinTitle,
        confirm: l10n.classesWaitlistJoinConfirm,
        note: confirmMinutes > 0
            ? l10n.classesWaitlistJoinInfoConfirm(confirmMinutes)
            : l10n.classesWaitlistJoinInfo,
        entryNote: l10n.classesWaitlistJoinEntry,
        footnote: l10n.classesWaitlistJoinFallback,
      ),
      send: (passId) =>
          ref.read(groupClassesApiProvider).joinWaitlist(widget.query, passId),
      success: l10n.classesWaitlistJoinSuccess,
    );
  }

  Future<void> _cancel(GroupClass event, ClassReservation reservation) async {
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () => _confirm(
        event,
        title: l10n.classesCancelTitle,
        consequence: reservation.makeupCreditId != null
            ? reservation.cancellation.entryReturned == true
                  ? l10n.makeupCancelReturns
                  : l10n.makeupCancelForfeits
            : reservation.customerPassId == null
            ? l10n.classesCancelWithoutPass
            : reservation.cancellation.entryReturned == true
            ? l10n.classesCancelReturnsEntry
            : l10n.classesCancelForfeitsEntry,
        keep: l10n.classesKeepReservation,
        confirm: l10n.classesCancel,
      ),
      send: (_) => ref
          .read(groupClassesApiProvider)
          .cancel(widget.query.scope, reservation),
      success: l10n.classesCancelSuccess,
    );
  }

  Future<void> _leaveWaitlist(
    GroupClass event,
    ClassWaitlistEntry entry,
  ) async {
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () => _confirm(
        event,
        title: l10n.classesWaitlistLeaveTitle,
        consequence: l10n.classesWaitlistLeaveConsequence,
        keep: l10n.classesWaitlistStay,
        confirm: l10n.classesWaitlistLeaveConfirm,
      ),
      send: (_) => ref
          .read(groupClassesApiProvider)
          .leaveWaitlist(widget.query.scope, entry),
      success: l10n.classesWaitlistLeaveSuccess,
    );
  }

  Future<void> _confirmOffer(GroupClass event, ClassWaitlistEntry entry) async {
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () => _confirm(
        event,
        title: l10n.classesWaitlistConfirmTitle,
        consequence: l10n.classesWaitlistConfirmConsequence,
        keep: l10n.classesDismiss,
        confirm: l10n.classesWaitlistConfirmOffer,
      ),
      send: (_) => ref
          .read(groupClassesApiProvider)
          .confirmWaitlistOffer(widget.query.scope, entry),
      success: l10n.classesWaitlistConfirmSuccess,
    );
  }

  Future<void> _declineOffer(GroupClass event, ClassWaitlistEntry entry) async {
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () => _confirm(
        event,
        title: l10n.classesWaitlistDeclineTitle,
        consequence: l10n.classesWaitlistDeclineConsequence,
        keep: l10n.classesDismiss,
        confirm: l10n.classesWaitlistDecline,
      ),
      send: (_) => ref
          .read(groupClassesApiProvider)
          .leaveWaitlist(widget.query.scope, entry),
      success: l10n.classesWaitlistDeclineSuccess,
    );
  }

  /// Sends one action for the screen's participant once [ask] returns a
  /// value (`null` dismisses it). The selected profile may change while a
  /// dialog is open, so nothing is sent for a participant no longer chosen.
  Future<void> _act<T extends Object>({
    required Future<T?> Function() ask,
    required Future<void> Function(T value) send,
    required String success,
  }) async {
    if (_busy || !isCurrentBookingScope(ref, widget.query.scope)) return;
    setState(() {
      _busy = true;
      _actionMessage = null;
    });
    try {
      final value = await ask();
      if (value == null ||
          !mounted ||
          !isCurrentBookingScope(ref, widget.query.scope)) {
        return;
      }
      setState(() => _sending = true);
      await send(value);
      _refreshAfterMutation();
      if (mounted && isCurrentBookingScope(ref, widget.query.scope)) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(success)));
      }
    } catch (error) {
      _onActionError(error);
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _sending = false;
        });
      }
    }
  }

  /// The pass for a booking or for a place from the waiting list: a single
  /// eligible pass is preselected, several require an explicit choice.
  Future<String?> _choosePass(
    GroupClass event, {
    required String title,
    required String confirm,
    required String entryNote,
    String? note,
    String? footnote,
  }) {
    final l10n = AppLocalizations.of(context);
    final participant = _participantName();
    final passes = event.booking.eligiblePasses;
    String? selectedPass = passes.length == 1 ? passes.single.id : null;
    return showDialog<String>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(title),
          scrollable: true,
          content: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            spacing: 12,
            children: [
              Text(event.name ?? l10n.classesTitle),
              Text(classDateTime(event.startsAt)),
              if (participant != null)
                Text(l10n.classesReserveFor(participant)),
              if (passes.length == 1)
                Text(_passLabel(l10n, passes.single))
              else
                DropdownButtonFormField<String>(
                  isExpanded: true,
                  decoration: InputDecoration(
                    labelText: l10n.classesChoosePass,
                    border: const OutlineInputBorder(),
                  ),
                  items: [
                    for (final pass in passes)
                      DropdownMenuItem(
                        value: pass.id,
                        child: Text(
                          _passLabel(l10n, pass),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: (id) => setDialogState(() => selectedPass = id),
                ),
              if (note != null) Text(note),
              if (passes
                      .where((pass) => pass.id == selectedPass)
                      .firstOrNull
                      ?.entriesRemaining !=
                  null)
                Text(entryNote),
              if (footnote != null) Text(footnote),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(l10n.classesDismiss),
            ),
            FilledButton(
              onPressed: selectedPass == null
                  ? null
                  : () => Navigator.of(context).pop(selectedPass),
              child: Text(confirm),
            ),
          ],
        ),
      ),
    );
  }

  /// Confirms giving up a place: `true` confirms, `null` keeps the place.
  Future<bool?> _confirm(
    GroupClass event, {
    required String title,
    required String consequence,
    required String keep,
    required String confirm,
  }) {
    final l10n = AppLocalizations.of(context);
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        scrollable: true,
        content: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          spacing: 12,
          children: [
            Text(event.name ?? l10n.classesTitle),
            Text(classDateTime(event.startsAt)),
            if (_participantName() case final participant?)
              Text(l10n.classesReserveFor(participant)),
            Text(consequence),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(keep),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(confirm),
          ),
        ],
      ),
    );
  }

  void _refreshAfterMutation() {
    if (!mounted) return;
    ref.invalidate(groupClassesProvider);
    ref.invalidate(classReservationsProvider);
    ref.invalidate(groupClassProvider(widget.query));
    ref.invalidate(classParticipantsProvider(widget.query));
    ref.invalidate(ownPassesProvider(widget.query.scope.profileId));
  }

  void _onActionError(Object error) {
    if (!mounted) return;
    _refreshAfterMutation();
    if (!isCurrentBookingScope(ref, widget.query.scope)) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _actionMessage = switch (error) {
        ClassBookingException(:final code, :final statusCode)
            when code == 'CANCELLATION_CONSEQUENCE_CHANGED' ||
                code == 'VERSION_CONFLICT' ||
                (statusCode == 409 && code == null) =>
          l10n.classesConditionsChanged,
        ClassBookingException(:final code) => classReason(l10n, code),
        _ => l10n.classesActionFailed,
      };
    });
    if (_scrollController.hasClients) _scrollController.jumpTo(0);
  }

  String _passLabel(AppLocalizations l10n, EligibleClassPass pass) {
    return [
      pass.productName,
      pass.entriesRemaining == null
          ? l10n.passEntriesUnlimited
          : l10n.classesPassEntries(pass.entriesRemaining!),
      if (pass.validUntil != null)
        l10n.passValidUntil(DateFormat('dd.MM.yyyy').format(pass.validUntil!)),
    ].join(' · ');
  }

  String? _participantName() => clientSession(ref.read(authControllerProvider))
      ?.profiles
      .where((profile) => profile.id == widget.query.scope.profileId)
      .firstOrNull
      ?.displayName;
}
