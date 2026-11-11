import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/client_schedule_event_tile.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/data/training_history_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

String personalEffect(AppLocalizations l10n, String? effect) =>
    switch (effect) {
      'MAKEUP_GRANTED' => l10n.personalMakeupGranted,
      'MAKEUP_RETURNED' => l10n.personalMakeupReturned,
      'ENTRY_FORFEITED' => l10n.personalEntryForfeited,
      _ => l10n.personalNoEntry,
    };

String personalStatus(AppLocalizations l10n, PersonalSession session) =>
    session.status == EventStatus.cancelled
    ? l10n.eventStatusCancelled
    : session.dispute?.status == 'OPEN'
    ? l10n.personalDisputed
    : session.dispute != null
    ? l10n.personalDisputeResolved
    : session.status == EventStatus.scheduled
    ? l10n.personalScheduled
    : session.trainerConfirmedAt == null
    ? l10n.personalAwaitingTrainer
    : l10n.personalHeld;

class PersonalTrainingTile extends StatelessWidget {
  const PersonalTrainingTile({
    super.key,
    required this.session,
    required this.onTap,
    this.participant,
  });
  final PersonalSession session;
  final VoidCallback onTap;
  final Widget? participant;
  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final cancelled = session.status == EventStatus.cancelled;
    final trainers = session.staff
        .map((p) => p.displayName)
        .nonNulls
        .join(', ');
    return ClientScheduleCard(
      color: cancelled ? theme.colorScheme.error : theme.colorScheme.primary,
      time:
          '${DateFormat('HH:mm').format(session.startsAt)}–${DateFormat('HH:mm').format(session.endsAt)}',
      title: session.name ?? l10n.personalTitle,
      trainer: trainers.isEmpty ? l10n.classesInstructorPending : trainers,
      room: session.roomNames.isEmpty
          ? l10n.classesRoomPending
          : session.roomNames.join(', '),
      cancelled: cancelled,
      participant: participant,
      onTap: onTap,
      footer: [
        ScheduleEventMarker(
          cancelled ? Icons.cancel_outlined : Icons.person_outline,
          personalStatus(l10n, session),
          color: cancelled ? theme.colorScheme.error : null,
        ),
      ],
    );
  }
}

class PersonalTrainingScreen extends ConsumerStatefulWidget {
  const PersonalTrainingScreen({super.key, required this.query});
  final PersonalSessionQuery query;
  @override
  ConsumerState<PersonalTrainingScreen> createState() =>
      _PersonalTrainingScreenState();
}

class _PersonalTrainingScreenState extends ConsumerState<PersonalTrainingScreen>
    with WidgetsBindingObserver {
  bool _busy = false;
  String? _error;
  bool get _current =>
      personalTrainingScope(ref.read(authControllerProvider)) ==
      widget.query.scope;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _current) {
      ref.invalidate(personalSessionProvider(widget.query));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final valid =
        personalTrainingScope(ref.watch(authControllerProvider)) ==
        widget.query.scope;
    final profile = clientSession(
      ref.watch(authControllerProvider),
    )?.profiles.where((p) => p.id == widget.query.scope.profileId).firstOrNull;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.personalTitle)),
      body: !valid
          ? ClassMessage(l10n.classesProfileChanged)
          : ref
                .watch(personalSessionProvider(widget.query))
                .when(
                  skipLoadingOnRefresh: false,
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (error, _) => Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(_failure(l10n, error)),
                        TextButton(
                          onPressed: () => ref.invalidate(
                            personalSessionProvider(widget.query),
                          ),
                          child: Text(l10n.passesRetryButton),
                        ),
                      ],
                    ),
                  ),
                  data: (session) => RefreshIndicator(
                    onRefresh: () => ref.refresh(
                      personalSessionProvider(widget.query).future,
                    ),
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.all(16),
                      children: [
                        if (profile != null) ...[
                          Text(
                            profile.displayName,
                            style: Theme.of(context).textTheme.labelMedium,
                          ),
                          const SizedBox(height: 6),
                        ],
                        Text(
                          session.name ?? l10n.personalTitle,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          '${classDateTime(session.startsAt)}–${DateFormat('HH:mm').format(session.endsAt)}',
                        ),
                        Text(
                          session.staff
                              .map((p) => p.displayName)
                              .nonNulls
                              .join(', '),
                        ),
                        if (session.roomNames.isNotEmpty)
                          Text(session.roomNames.join(', ')),
                        const SizedBox(height: 12),
                        Align(
                          alignment: Alignment.centerLeft,
                          child: Chip(
                            label: Text(personalStatus(l10n, session)),
                          ),
                        ),
                        Card.outlined(
                          child: ListTile(
                            leading: const Icon(
                              Icons.confirmation_number_outlined,
                            ),
                            title: Text(
                              session.passName ?? l10n.personalNoPass,
                            ),
                            subtitle: session.cancellationEffect == null
                                ? null
                                : Text(switch (session.cancellationEffect) {
                                    'ENTRY_FORFEITED' =>
                                      l10n.personalEntryWasForfeited,
                                    'MAKEUP_GRANTED' =>
                                      l10n.personalMakeupWasGranted,
                                    _ => personalEffect(
                                      l10n,
                                      session.cancellationEffect,
                                    ),
                                  }),
                          ),
                        ),
                        if (_error != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Text(
                              _error!,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                        if (session.cancellation.allowed) ...[
                          const SizedBox(height: 12),
                          if (session.cancellation.freeCancelUntil
                              case final deadline?)
                            Text(
                              session.cancellation.late
                                  ? l10n.personalCancelDeadlinePassed(
                                      classDateTime(deadline),
                                    )
                                  : l10n.personalCancelDeadline(
                                      classDateTime(deadline),
                                    ),
                              style: Theme.of(context).textTheme.bodySmall
                                  ?.copyWith(
                                    color: session.cancellation.late
                                        ? Theme.of(context).colorScheme.error
                                        : null,
                                  ),
                            ),
                          const SizedBox(height: 8),
                          OutlinedButton.icon(
                            onPressed: _busy ? null : () => _cancel(session),
                            icon: const Icon(Icons.event_busy_outlined),
                            label: Text(l10n.personalCancel),
                          ),
                        ] else if (session.status == EventStatus.scheduled)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Text(
                              classReason(l10n, session.cancellation.reason),
                            ),
                          ),
                        if (session.dispute case final dispute?) ...[
                          const SizedBox(height: 12),
                          Card.outlined(
                            child: Padding(
                              padding: const EdgeInsets.all(14),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                spacing: 8,
                                children: [
                                  Text(
                                    dispute.status == 'OPEN'
                                        ? l10n.personalDisputed
                                        : l10n.personalDisputeResolved,
                                    style: Theme.of(
                                      context,
                                    ).textTheme.titleSmall,
                                  ),
                                  Text(dispute.reason),
                                  if (dispute.resolutionNote case final note?)
                                    Text(note),
                                  if (dispute.status == 'OPEN')
                                    Text(
                                      l10n.personalDisputePending,
                                      style: Theme.of(
                                        context,
                                      ).textTheme.bodySmall,
                                    ),
                                ],
                              ),
                            ),
                          ),
                        ] else if (session.canDispute) ...[
                          const SizedBox(height: 12),
                          if (session.disputeClosesAt case final deadline?)
                            Text(
                              l10n.personalDisputeDeadline(
                                classDateTime(deadline),
                              ),
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          OutlinedButton.icon(
                            onPressed: _busy ? null : () => _dispute(session),
                            icon: const Icon(Icons.report_problem_outlined),
                            label: Text(l10n.personalReport),
                          ),
                        ] else if (session.disputeReason ==
                            'DISPUTE_WINDOW_CLOSED')
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Text(l10n.personalDisputeClosed),
                          ),
                        if (_busy) const LinearProgressIndicator(),
                      ],
                    ),
                  ),
                ),
    );
  }

  Future<void> _cancel(PersonalSession session) async {
    if (_busy || !_current) return;
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => Consumer(
        builder: (context, ref, _) {
          final valid =
              personalTrainingScope(ref.watch(authControllerProvider)) ==
              widget.query.scope;
          final positive =
              session.cancellation.effect == 'MAKEUP_GRANTED' ||
              session.cancellation.effect == 'MAKEUP_RETURNED';
          return AlertDialog(
            scrollable: true,
            title: Text(
              valid ? l10n.personalCancelConfirm : l10n.classesProfileChanged,
            ),
            content: !valid
                ? null
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: 12,
                    children: [
                      Text(classDateTime(session.startsAt)),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: positive
                              ? Colors.green.shade50
                              : Theme.of(context).colorScheme.errorContainer,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              positive
                                  ? Icons.event_repeat
                                  : Icons.info_outline,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                personalEffect(
                                  l10n,
                                  session.cancellation.effect,
                                ),
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (session.cancellation.validityDays case final days?)
                        Text(l10n.personalMakeupValidity(days)),
                      if (session.cancellation.late &&
                          session.cancellation.freeCancelUntil != null)
                        Text(
                          l10n.personalCancelDeadlinePassed(
                            classDateTime(
                              session.cancellation.freeCancelUntil!,
                            ),
                          ),
                        ),
                      if (session.cancellation.effect == 'MAKEUP_RETURNED')
                        Text(l10n.personalOriginalExpiry),
                      if (session.cancellation.consequenceReason ==
                          'MAKEUP_LIMIT_REACHED')
                        Text(l10n.personalMakeupLimit),
                      if (session.cancellation.consequenceReason ==
                          'MAKEUP_NOT_GRANTED')
                        Text(l10n.personalMakeupUnavailable),
                    ],
                  ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: Text(l10n.classesKeepReservation),
              ),
              if (valid)
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(true),
                  child: Text(l10n.personalCancel),
                ),
            ],
          );
        },
      ),
    );
    if (confirmed != true || !mounted || !_current) return;
    await _send(
      () => ref
          .read(personalTrainingsApiProvider)
          .cancel(widget.query.scope, session),
    );
  }

  Future<void> _dispute(PersonalSession session) async {
    if (_busy || !_current) return;
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => _DisputeDialog(query: widget.query),
    );
    if (reason == null || !mounted || !_current) return;
    await _send(
      () => ref
          .read(personalTrainingsApiProvider)
          .dispute(widget.query.scope, session, reason),
    );
  }

  Future<void> _send(Future<PersonalSession> Function() send) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await send();
      if (!mounted || !_current) return;
      _refresh();
    } catch (error) {
      if (!mounted || !_current) return;
      _refresh();
      setState(() => _error = _failure(AppLocalizations.of(context), error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _refresh() {
    ref.invalidate(personalSessionProvider(widget.query));
    ref.invalidate(personalTrainingsProvider);
    ref.invalidate(trainingHistoryProvider);
    ref.invalidate(makeupCreditsProvider);
  }

  String _failure(AppLocalizations l10n, Object error) => switch (error) {
    ClassBookingException(
      code: 'VERSION_CONFLICT' || 'CANCELLATION_CONSEQUENCE_CHANGED',
    ) =>
      l10n.personalChanged,
    ClassBookingException(code: 'DISPUTE_WINDOW_CLOSED') =>
      l10n.personalDisputeClosed,
    _ => l10n.personalLoadError,
  };
}

class _DisputeDialog extends ConsumerStatefulWidget {
  const _DisputeDialog({required this.query});
  final PersonalSessionQuery query;
  @override
  ConsumerState<_DisputeDialog> createState() => _DisputeDialogState();
}

class _DisputeDialogState extends ConsumerState<_DisputeDialog> {
  final _reason = TextEditingController();
  bool _submitted = false;
  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final valid =
        personalTrainingScope(ref.watch(authControllerProvider)) ==
        widget.query.scope;
    return AlertDialog(
      scrollable: true,
      title: Text(valid ? l10n.personalReport : l10n.classesProfileChanged),
      content: valid
          ? Column(
              mainAxisSize: MainAxisSize.min,
              spacing: 12,
              children: [
                Text(l10n.personalDisputePending),
                TextField(
                  controller: _reason,
                  autofocus: true,
                  minLines: 2,
                  maxLines: 4,
                  maxLength: 1000,
                  decoration: InputDecoration(
                    labelText: l10n.personalDisputeReason,
                    errorText: _submitted && _reason.text.trim().isEmpty
                        ? l10n.personalReasonRequired
                        : null,
                  ),
                ),
              ],
            )
          : null,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.personalBack),
        ),
        if (valid)
          FilledButton(
            onPressed: () {
              setState(() => _submitted = true);
              if (_reason.text.trim().isNotEmpty) {
                Navigator.of(context).pop(_reason.text.trim());
              }
            },
            child: Text(l10n.personalSend),
          ),
      ],
    );
  }
}
