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
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Opens the class sheet of [query] over the current screen.
Future<GroupClassSheetResult?> showGroupClassSheet(
  BuildContext context,
  ClassQuery query,
) => showAppSheet<GroupClassSheetResult>(
  context,
  builder: (_) => GroupClassDetailsScreen(query: query),
);

/// What the class sheet asks of the screen below when it closes.
enum GroupClassSheetResult {
  /// Show the full offer, to pick another class.
  browse,
}

/// The class sheet (#281): what the class is, and booking, cancelling, the
/// waiting list and a freed place for the selected participant.
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
  String? _actionMessage;

  /// The pass chosen in the sheet; the one that ends first until changed.
  String? _passId;

  /// The list of eligible passes is open.
  bool _choosingPass = false;

  static final _date = DateFormat('dd.MM.yyyy');
  static final _time = DateFormat('HH:mm');

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final activeScope = bookingScope(ref.watch(authControllerProvider));
    if (activeScope != widget.query.scope) {
      return AppSheet(
        title: l10n.classesDetails,
        titleSmall: true,
        footer: AppButton(
          label: l10n.classesBack,
          expand: true,
          onPressed: () => Navigator.of(context).maybePop(),
        ),
        children: [SheetWarning(l10n.classesProfileChanged)],
      );
    }
    final provider = groupClassProvider(widget.query);
    return ref
        .watch(provider)
        .when(
          skipLoadingOnRefresh: true,
          skipLoadingOnReload: true,
          loading: () => AppSheet(
            kicker: l10n.classesTitle,
            children: [
              Padding(
                padding: EdgeInsets.all(context.ct.spacing.xl * 2),
                child: const Center(child: CircularProgressIndicator()),
              ),
            ],
          ),
          error: (_, _) => AppSheet(
            kicker: l10n.classesTitle,
            children: [
              ClassesLoadError(onRetry: () => ref.invalidate(provider)),
            ],
          ),
          data: _sheet,
        );
  }

  Widget _sheet(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final participant = _participantName();
    final kicker = [
      event.serviceTypeName ?? l10n.classesTitle,
      ?participant,
    ].join(' · ');
    final reservation = event.ownReservation;
    final waiting = event.isWaitlisted ? event.waitlist.ownEntry : null;
    final cancelled = event.status == 'CANCELLED';

    final List<Widget> body;
    final Widget? footer;
    if (cancelled) {
      final booked = reservation != null;
      body = [
        ChangeStrip(
          booked && reservation.entryReturned == true
              ? l10n.classesCancelledEntryBack
              : l10n.classesCancelledStrip,
        ),
        ..._facts(event),
      ];
      // Back to the full offer, to pick another class instead.
      footer = booked
          ? AppButton.primary(
              label: l10n.classesBrowseOther,
              onPressed: () =>
                  Navigator.of(context).pop(GroupClassSheetResult.browse),
            )
          : null;
    } else if (event.isReserved && reservation != null) {
      (body, footer) = _reserved(event, reservation);
    } else if (waiting != null && waiting.isOffered) {
      (body, footer) = _offered(event, waiting);
    } else if (waiting != null) {
      (body, footer) = _waiting(event, waiting);
    } else if (event.booking.reason == 'CLASS_FULL' &&
        event.waitlist.reason != 'WAITLIST_DISABLED') {
      (body, footer) = _full(event);
    } else {
      (body, footer) = _bookable(event);
    }

    return AppSheet(
      kicker: kicker,
      title: event.name ?? l10n.classesTitle,
      subtitle: _when(l10n, event),
      footer: footer,
      children: [
        if (_actionMessage case final message?) ChangeStrip(message),
        if (cancelled ? null : _change(l10n, event) case final change?)
          ChangeStrip(change),
        ...body,
        // Who else comes is shown only to those who booked.
        if (!cancelled && event.isReserved)
          ClassParticipantsSection(query: widget.query),
      ],
    );
  }

  String _when(AppLocalizations l10n, GroupClass event) {
    final now = DateTime.now();
    final range =
        '${_time.format(event.startsAt)}–${_time.format(event.endsAt)}';
    final today =
        event.startsAt.year == now.year &&
        event.startsAt.month == now.month &&
        event.startsAt.day == now.day;
    return today
        ? l10n.classesWhenToday(range)
        : '${DateFormat('EEE d.MM', 'pl').format(event.startsAt)} $range';
  }

  String? _change(AppLocalizations l10n, GroupClass event) {
    final change = event.seriesChange;
    if (change == null) return null;
    return [
      if (change.usualStartsAt case final usual?)
        l10n.classesChangeTime(
          DateFormat('EEE d.MM HH:mm', 'pl').format(usual),
        ),
      if (change.usualInstructors case final names? when names.isNotEmpty)
        l10n.classesChangeSubstitution(names.join(', ')),
      if (change.usualRooms case final rooms? when rooms.isNotEmpty)
        l10n.classesChangeRoom(rooms.join(', ')),
    ].join('\n');
  }

  /// Who runs the class, where and how many places are free.
  List<Widget> _facts(GroupClass event, {bool placeHeld = false}) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final leads = [
      for (final person in event.staff)
        if (person.isLead && person.displayName != null) person.displayName!,
    ];
    final places = event.availablePlaces;
    return [
      SheetKeyValues(
        entries: [
          SheetKeyValues.text(
            l10n.classesInstructor,
            leads.isEmpty ? l10n.classesInstructorPending : leads.join(', '),
          ),
          SheetKeyValues.text(
            l10n.classesRoom,
            event.roomNames.isEmpty
                ? l10n.classesRoomPending
                : event.roomNames.join(', '),
          ),
          (
            l10n.classesPlacesLabel,
            placeHeld
                ? Text(l10n.classesPlaceWaiting, style: ct.text.body)
                : places == null || event.capacity == null
                ? Text(l10n.classesPlacesOpen, style: ct.text.body)
                : Text.rich(
                    TextSpan(
                      children: [
                        TextSpan(
                          text: '${math.max(places, 0)} ',
                          style: ct.text.placesNumber,
                        ),
                        TextSpan(
                          text: l10n.classesPlacesOf(event.capacity!),
                          style: ct.text.body,
                        ),
                      ],
                    ),
                  ),
          ),
        ],
      ),
    ];
  }

  String _passName(EligibleClassPass pass) {
    final l10n = AppLocalizations.of(context);
    return pass.entriesRemaining == null
        ? l10n.classesPassUnlimited(pass.productName)
        : l10n.classesPassLeft(
            pass.productName,
            math.max(pass.entriesRemaining! - 1, 0),
          );
  }

  /// The pass line; with several eligible passes, "Zmień" opens the choice.
  List<Widget> _passChoice(GroupClass event, {bool later = false}) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final passes = event.booking.eligiblePasses;
    if (passes.isEmpty) return const [];
    final selected = _selectedPass(event);
    final pass = passes.firstWhere((pass) => pass.id == selected);
    if (!_choosingPass || passes.length == 1) {
      return [
        SheetLine(
          label: l10n.classesFromPass,
          value: later
              ? l10n.classesPassLater(pass.productName)
              : _passName(pass),
          actionLabel: passes.length > 1 ? l10n.classesPassChange : null,
          onAction: () => setState(() => _choosingPass = true),
        ),
      ];
    }
    return [
      Container(
        padding: EdgeInsets.only(top: ct.spacing.m, bottom: ct.spacing.s),
        decoration: BoxDecoration(border: Border(top: ct.hairline)),
        child: Text(l10n.classesFromPass, style: ct.text.lineLabel),
      ),
      ChoiceOptions<String>(
        options: [
          for (final pass in passes)
            ChoiceOption(
              value: pass.id,
              title: pass.productName,
              subtitle: l10n.classesPassOption(
                pass.entriesRemaining == null
                    ? l10n.passEntriesUnlimited
                    : l10n.classesPassEntries(pass.entriesRemaining!),
                pass.validUntil == null ? '—' : _date.format(pass.validUntil!),
              ),
            ),
        ],
        selected: selected,
        onChanged: (id) => setState(() {
          _passId = id;
          _choosingPass = false;
        }),
      ),
    ];
  }

  /// The chosen pass; until one is chosen, the one that ends first.
  String? _selectedPass(GroupClass event) {
    final passes = event.booking.eligiblePasses;
    if (passes.any((pass) => pass.id == _passId)) return _passId;
    if (passes.isEmpty) return null;
    final far = DateTime(9999);
    return passes
        .reduce(
          (a, b) => (b.validUntil ?? far).isBefore(a.validUntil ?? far) ? b : a,
        )
        .id;
  }

  (List<Widget>, Widget?) _bookable(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final canReserve =
        event.booking.allowed && event.booking.eligiblePasses.isNotEmpty;
    final reason = event.booking.reason ?? 'NO_ELIGIBLE_PASS';
    return (
      [
        ..._facts(event),
        ..._passChoice(event),
        if (!canReserve) SheetWarning(classReason(l10n, reason)),
        if (!canReserve && reason == 'REQUIRED_DOCUMENTS_MISSING')
          _documentsButton(l10n),
      ],
      AppButton.primary(
        label: l10n.classesReserve,
        loading: _busy,
        onPressed: canReserve ? () => _reserve(event) : null,
      ),
    );
  }

  (List<Widget>, Widget?) _reserved(
    GroupClass event,
    ClassReservation reservation,
  ) {
    final l10n = AppLocalizations.of(context);
    // The studio may book a place without a pass entry; cancelling it costs
    // nothing.
    final withoutPass =
        reservation.customerPassId == null &&
        reservation.makeupCreditId == null;
    final funding = reservation.cancellation.passConsequence;
    final change = event.seriesChange;
    return (
      [
        ..._facts(event),
        SheetLine(
          label: l10n.classesReservedWith,
          value: reservation.makeupCreditId != null
              ? l10n.makeupReservation
              : withoutPass
              ? l10n.classesReservedWithoutPass
              : funding?.productName ?? l10n.classesReservedFromPass,
        ),
        if (reservation.freeCancelUntil case final deadline?
            when reservation.cancellation.allowed &&
                reservation.cancellation.entryReturned == true) ...[
          SheetWarning(l10n.classesFreeCancelUntil(classDateTime(deadline))),
          if (change?.moved ?? false) SheetWarning(l10n.changeFreeCancelMoved),
        ],
        if (!reservation.cancellation.allowed ||
            (!withoutPass && reservation.cancellation.entryReturned != true))
          SheetWarning(
            reservation.cancellation.allowed
                ? reservation.makeupCreditId != null
                      ? l10n.makeupCancelForfeits
                      : _forfeitedEntry(l10n, reservation)
                : classReason(l10n, reservation.cancellation.reason),
          ),
      ],
      AppButton(
        label: l10n.classesCancel,
        expand: true,
        loading: _busy,
        onPressed: reservation.cancellation.allowed
            ? () => _cancel(event, reservation)
            : null,
      ),
    );
  }

  /// A full class with a waiting list: an invitation to join it, or why the
  /// participant cannot.
  (List<Widget>, Widget?) _full(GroupClass event) {
    final l10n = AppLocalizations.of(context);
    final waitlist = event.waitlist;
    final canJoin = waitlist.allowed && event.booking.eligiblePasses.isNotEmpty;
    final reason = waitlist.reason ?? 'NO_ELIGIBLE_PASS';
    return (
      [
        ..._facts(event),
        SheetLine(
          label: l10n.classesWaitlistLabel,
          value: waitlist.waitingCount > 0
              ? l10n.classesWaitlistWaitingCount(waitlist.waitingCount)
              : l10n.classesWaitlistNobody,
        ),
        if (canJoin) ...[
          ..._passChoice(event, later: true),
          SheetWarning(
            waitlist.confirmationMinutes > 0
                ? l10n.classesWaitlistJoinInfoConfirm(
                    waitlist.confirmationMinutes,
                  )
                : l10n.classesWaitlistJoinInfo,
          ),
          SheetWarning(l10n.classesWaitlistJoinFallback),
        ] else ...[
          SheetWarning(classReason(l10n, reason)),
          if (reason == 'REQUIRED_DOCUMENTS_MISSING') _documentsButton(l10n),
        ],
      ],
      AppButton.primary(
        label: l10n.classesWaitlistJoin,
        loading: _busy,
        onPressed: canJoin ? () => _joinWaitlist(event) : null,
      ),
    );
  }

  /// The participant waits for a place: their position, whether a freed
  /// place would be given to them now, and a way out of the queue.
  (List<Widget>, Widget?) _waiting(GroupClass event, ClassWaitlistEntry entry) {
    final l10n = AppLocalizations.of(context);
    final canReserve =
        event.booking.allowed && event.booking.eligiblePasses.isNotEmpty;
    final blocked = entry.promotionBlockedReason;
    final confirmMinutes = event.waitlist.confirmationMinutes;
    final position = entry.position;
    return (
      [
        WaitlistBox(
          number: position == null
              ? '–'
              : l10n.classesWaitlistPositionNumber(position),
          title: l10n.classesWaitlistJoined,
          subtitle: position == null
              ? null
              : l10n.classesWaitlistPosition(
                  position,
                  // A count read a moment apart never shows "3 of 2".
                  math.max(position, event.waitlist.waitingCount),
                ),
        ),
        ..._facts(event),
        if (blocked != null) ...[
          SheetWarning(switch (blocked) {
            'BOOKING_CLOSED' ||
            'EVENT_NOT_BOOKABLE' => l10n.classesWaitlistClosed,
            _ => l10n.classesWaitlistBlocked(classReason(l10n, blocked)),
          }),
          if (blocked == 'REQUIRED_DOCUMENTS_MISSING') _documentsButton(l10n),
        ] else
          // Places are given only when a reservation is cancelled. One that
          // is free now was skipped by the queue, so the participant books it.
          SheetWarning(
            canReserve
                ? l10n.classesWaitlistPlaceFreed
                : confirmMinutes > 0
                ? l10n.classesWaitlistConfirmNotice(confirmMinutes)
                : l10n.classesWaitlistAutoBooking,
          ),
        if (canReserve) ..._passChoice(event),
      ],
      Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: context.ct.spacing.s,
        children: [
          if (canReserve)
            AppButton.primary(
              label: l10n.classesReserve,
              loading: _busy,
              onPressed: () => _reserve(event),
            ),
          Center(
            child: AppButton(
              label: l10n.classesWaitlistLeave,
              onPressed: _busy ? null : () => _leaveWaitlist(event, entry),
            ),
          ),
        ],
      ),
    );
  }

  /// A freed place held for the participant: confirm it before the deadline
  /// or give it to the next one on the list.
  (List<Widget>, Widget?) _offered(GroupClass event, ClassWaitlistEntry entry) {
    final l10n = AppLocalizations.of(context);
    final blocked = entry.promotionBlockedReason;
    final deadline = entry.offerExpiresAt;
    final open = deadline == null || DateTime.now().isBefore(deadline);
    final passes = event.booking.eligiblePasses;
    final chosen = passes
        .where((pass) => pass.id == entry.customerPassId)
        .firstOrNull;
    return (
      [
        ChangeStrip(
          open
              ? '${l10n.classesWaitlistOffered}. '
                    '${l10n.classesWaitlistOfferDeadline(deadline == null ? '' : classDateTime(deadline))}'
              : l10n.classesWaitlistOfferExpired,
        ),
        ..._facts(event, placeHeld: open),
        if (chosen != null)
          SheetLine(label: l10n.classesFromPass, value: _passName(chosen)),
        if (blocked != null && open) ...[
          SheetWarning(
            l10n.classesWaitlistOfferBlocked(classReason(l10n, blocked)),
          ),
          if (blocked == 'REQUIRED_DOCUMENTS_MISSING') _documentsButton(l10n),
        ],
      ],
      Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: context.ct.spacing.s,
        children: [
          AppButton.primary(
            label: l10n.classesWaitlistConfirmOffer,
            loading: _busy,
            onPressed: !open || blocked != null
                ? null
                : () => _confirmOffer(event, entry),
          ),
          Center(
            child: AppButton(
              label: l10n.classesWaitlistDecline,
              onPressed: _busy || !open
                  ? null
                  : () => _declineOffer(event, entry),
            ),
          ),
        ],
      ),
    );
  }

  Widget _documentsButton(AppLocalizations l10n) => Padding(
    padding: EdgeInsets.only(top: context.ct.spacing.m),
    child: Align(
      alignment: Alignment.centerLeft,
      child: AppButton(
        label: l10n.classesOpenDocuments,
        small: true,
        icon: Icons.description_outlined,
        onPressed: () => context.push(documentsRoute),
      ),
    ),
  );

  Future<void> _reserve(GroupClass event) async {
    final passId = _selectedPass(event);
    if (passId == null) return;
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () async => passId,
      send: (passId) =>
          ref.read(groupClassesApiProvider).reserve(widget.query, passId),
      success: l10n.classesBookingSuccess,
    );
  }

  Future<void> _joinWaitlist(GroupClass event) async {
    final passId = _selectedPass(event);
    if (passId == null) return;
    final l10n = AppLocalizations.of(context);
    await _act(
      ask: () async => passId,
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
        consequences: _cancellationConsequences(l10n, reservation),
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
        consequences: [
          Consequence(
            icon: Icons.playlist_remove,
            title: l10n.classesWaitlistLeaveConsequence,
          ),
        ],
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
      ask: () async => true,
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
        consequences: [
          Consequence(
            icon: Icons.east,
            title: l10n.classesWaitlistDeclineConsequence,
          ),
        ],
        keep: l10n.classesDismiss,
        confirm: l10n.classesWaitlistDecline,
      ),
      send: (_) => ref
          .read(groupClassesApiProvider)
          .leaveWaitlist(widget.query.scope, entry),
      success: l10n.classesWaitlistDeclineSuccess,
    );
  }

  /// Sends one action for the sheet's participant once [ask] returns a
  /// value (`null` dismisses it). The selected profile may change while a
  /// confirmation is open, so nothing is sent for a participant no longer
  /// chosen.
  Future<void> _act<T extends Object>({
    required Future<T?> Function() ask,
    required Future<void> Function(T value) send,
    required String success,
  }) async {
    if (_busy || !isCurrentBookingScope(ref, widget.query.scope)) return;
    setState(() => _actionMessage = null);
    try {
      final value = await ask();
      if (value == null ||
          !mounted ||
          !isCurrentBookingScope(ref, widget.query.scope)) {
        return;
      }
      setState(() => _busy = true);
      await send(value);
      _refreshAfterMutation();
      if (mounted && isCurrentBookingScope(ref, widget.query.scope)) {
        ScaffoldMessenger.maybeOf(
          context,
        )?.showSnackBar(SnackBar(content: Text(success)));
      }
    } catch (error) {
      _onActionError(error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Confirms giving up a place: `true` confirms, `null` keeps the place.
  Future<bool?> _confirm(
    GroupClass event, {
    required String title,
    required List<Consequence> consequences,
    required String keep,
    required String confirm,
  }) {
    final l10n = AppLocalizations.of(context);
    return showAppSheet<bool>(
      context,
      builder: (context) => AppSheet(
        title: title,
        titleSmall: true,
        subtitle: [
          event.name ?? l10n.classesTitle,
          classDateTime(event.startsAt),
          ?_participantName(),
        ].join(' · '),
        footer: AppButtonPair(
          first: AppButton(
            label: keep,
            small: true,
            onPressed: () => Navigator.of(context).pop(),
          ),
          second: AppButton(
            label: confirm,
            small: true,
            onPressed: () => Navigator.of(context).pop(true),
          ),
        ),
        children: [ConsequenceList(items: consequences)],
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
  }

  /// What cancelling does to the pass or make-up credit, as the server
  /// forecasts it. The server checks it again when the participant confirms.
  List<Consequence> _cancellationConsequences(
    AppLocalizations l10n,
    ClassReservation reservation,
  ) {
    final returned = reservation.cancellation.entryReturned == true;
    final funding = reservation.cancellation.passConsequence;
    if (reservation.makeupCreditId != null) {
      return [
        Consequence(
          icon: returned ? Icons.check : Icons.close,
          positive: returned,
          title: returned
              ? l10n.makeupCancelReturns
              : l10n.makeupCancelForfeits,
          subtitle: [
            if (funding != null)
              l10n.classesCancelMakeupCredit(funding.productName),
            if (funding?.expiresAt case final expiresAt?)
              l10n.makeupExpiresAt(_date.format(expiresAt)),
          ].join('\n').nullIfEmpty,
        ),
      ];
    }
    if (reservation.customerPassId == null) {
      return [
        Consequence(
          icon: Icons.check,
          positive: true,
          title: l10n.classesCancelWithoutPass,
        ),
      ];
    }
    final deadline = reservation.freeCancelUntil;
    return [
      Consequence(
        icon: returned ? Icons.check : Icons.close,
        positive: returned,
        title: returned
            ? l10n.classesConsequenceOnTime
            : l10n.classesConsequenceLate,
        subtitle: returned
            ? (deadline == null
                  ? l10n.classesCancelReturnsEntry
                  : l10n.classesFreeCancelUntil(classDateTime(deadline)))
            : _forfeitedEntry(l10n, reservation),
      ),
      if (funding != null)
        Consequence(
          icon: Icons.confirmation_number_outlined,
          title: funding.isUnlimitedPass
              ? l10n.classesCancelUnlimitedPass
              : switch ((
                  funding.entriesRemaining,
                  funding.entriesAfterCancellation,
                )) {
                  (final now?, final after?) =>
                    l10n.classesCancelEntriesBalance(now, after),
                  _ => l10n.classesCancelPass(funding.productName),
                },
          subtitle: [
            l10n.classesCancelPass(funding.productName),
            if (funding.validUntil case final validUntil?)
              l10n.passValidUntil(_date.format(validUntil)),
          ].join(' · '),
        ),
    ];
  }

  /// A forfeited entry is explained by the free cancellation deadline that
  /// has passed, when the reservation has one.
  String _forfeitedEntry(AppLocalizations l10n, ClassReservation reservation) {
    final deadline = reservation.freeCancelUntil;
    return deadline != null && deadline.isBefore(DateTime.now())
        ? l10n.classesCancelLateForfeitsEntry(classDateTime(deadline))
        : l10n.classesCancelForfeitsEntry;
  }

  String? _participantName() {
    final session = clientSession(ref.read(authControllerProvider));
    // Only a family account names whose class it is.
    if (session == null || session.profiles.length < 2) return null;
    return session.profiles
        .where((profile) => profile.id == widget.query.scope.profileId)
        .firstOrNull
        ?.displayName;
  }
}

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
