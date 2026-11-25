import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/messages_screen.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/data/training_history_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

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

/// Opens the personal training sheet of [query] over the current screen.
Future<void> showPersonalTrainingSheet(
  BuildContext context,
  PersonalSessionQuery query,
) => showAppSheet<void>(
  context,
  builder: (_) => PersonalTrainingScreen(query: query),
);

/// The personal training sheet (#281): when and with whom, the package, and
/// cancelling, reporting a mismatch or writing to the trainer.
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

  static final _time = DateFormat('HH:mm');

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
    if (!valid) {
      return AppSheet(
        title: l10n.personalTitle,
        titleSmall: true,
        footer: AppButton(
          label: l10n.classesBack,
          expand: true,
          onPressed: () => Navigator.of(context).maybePop(),
        ),
        children: [SheetWarning(l10n.classesProfileChanged)],
      );
    }
    final provider = personalSessionProvider(widget.query);
    return ref
        .watch(provider)
        .when(
          skipLoadingOnRefresh: true,
          skipLoadingOnReload: true,
          loading: () => AppSheet(
            kicker: l10n.personalTitle,
            children: [
              Padding(
                padding: EdgeInsets.all(context.ct.spacing.xl * 2),
                child: const Center(child: CircularProgressIndicator()),
              ),
            ],
          ),
          error: (error, _) => AppSheet(
            kicker: l10n.personalTitle,
            children: [
              SheetWarning(_failure(l10n, error)),
              Padding(
                padding: EdgeInsets.only(top: context.ct.spacing.m),
                child: AppButton(
                  label: l10n.passesRetryButton,
                  onPressed: () => ref.invalidate(provider),
                ),
              ),
            ],
          ),
          data: _sheet,
        );
  }

  String? _participantName() {
    final session = clientSession(ref.read(authControllerProvider));
    if (session == null || session.profiles.length < 2) return null;
    return session.profiles
        .where((p) => p.id == widget.query.scope.profileId)
        .firstOrNull
        ?.displayName;
  }

  String _when(PersonalSession session) {
    final start = session.startsAt.toLocal();
    final range = '${_time.format(start)}–${_time.format(session.endsAt)}';
    final now = DateTime.now();
    final today =
        start.year == now.year &&
        start.month == now.month &&
        start.day == now.day;
    return today
        ? AppLocalizations.of(context).classesWhenToday(range)
        : '${DateFormat('EEE d.MM', 'pl').format(start)} $range';
  }

  Widget _sheet(PersonalSession session) {
    final l10n = AppLocalizations.of(context);
    final cancelled = session.status == EventStatus.cancelled;
    final scheduled = session.status == EventStatus.scheduled;
    final trainers = [
      for (final person in session.staff) ?person.displayName,
    ].join(', ');
    final dispute = session.dispute;
    return AppSheet(
      kicker: [
        session.serviceTypeName ?? l10n.personalTitle,
        ?_participantName(),
      ].join(' · '),
      title: session.name ?? l10n.personalTitle,
      subtitle: _when(session),
      footer: _footer(session),
      children: [
        if (_error case final error?) ChangeStrip(error),
        if (cancelled)
          ChangeStrip(
            [
              l10n.eventStatusCancelled,
              if (session.cancellationEffect case final effect?)
                switch (effect) {
                  'ENTRY_FORFEITED' => l10n.personalEntryWasForfeited,
                  'MAKEUP_GRANTED' => l10n.personalMakeupWasGranted,
                  _ => personalEffect(l10n, effect),
                },
            ].join(' · '),
          )
        else if (!scheduled)
          _StatusLine(session: session),
        SheetKeyValues(
          entries: [
            SheetKeyValues.text(
              l10n.classesInstructor,
              trainers.isEmpty ? l10n.classesInstructorPending : trainers,
            ),
            SheetKeyValues.text(
              l10n.classesRoom,
              session.roomNames.isEmpty
                  ? l10n.classesRoomPending
                  : session.roomNames.join(', '),
            ),
            SheetKeyValues.text(
              l10n.personalPackage,
              session.passName ?? l10n.personalNoPass,
            ),
          ],
        ),
        if (scheduled && session.cancellation.allowed)
          if (session.cancellation.freeCancelUntil case final deadline?)
            SheetWarning(
              session.cancellation.late
                  ? l10n.personalCancelLate(classDateTime(deadline))
                  : l10n.personalCancelUntil(classDateTime(deadline)),
            ),
        if (scheduled && !session.cancellation.allowed)
          SheetWarning(classReason(l10n, session.cancellation.reason)),
        if (dispute != null) ...[
          SheetLine(
            label: dispute.status == 'OPEN'
                ? l10n.personalDisputed
                : l10n.personalDisputeResolved,
            value: dispute.reason,
          ),
          if (dispute.resolutionNote case final note?) SheetWarning(note),
          if (dispute.status == 'OPEN')
            SheetWarning(l10n.personalDisputePending),
        ] else if (session.canDispute) ...[
          if (session.disputeClosesAt case final deadline?)
            SheetWarning(l10n.personalDisputeUntil(classDateTime(deadline))),
        ] else if (session.disputeReason == 'DISPUTE_WINDOW_CLOSED')
          SheetWarning(l10n.personalDisputeClosed),
      ],
    );
  }

  Widget? _footer(PersonalSession session) {
    final l10n = AppLocalizations.of(context);
    final scheduled = session.status == EventStatus.scheduled;
    final trainer = session.staff
        .where((person) => person.displayName != null)
        .firstOrNull;
    final write = trainer == null || !scheduled
        ? null
        : AppButton(
            label: l10n.personalWrite,
            small: true,
            icon: Icons.chat_bubble_outline,
            onPressed: _busy ? null : () => _write(trainer.staffId),
          );
    final cancel = scheduled && session.cancellation.allowed
        ? AppButton(
            label: l10n.personalCancel,
            small: true,
            loading: _busy,
            onPressed: _busy ? null : () => _cancel(session),
          )
        : null;
    final report = session.dispute == null && session.canDispute
        ? AppButton(
            label: l10n.personalReport,
            small: true,
            loading: _busy,
            onPressed: _busy ? null : () => _dispute(session),
          )
        : null;
    final buttons = [?write, ?cancel, ?report];
    if (buttons.isEmpty) return null;
    return AppButtonPair(
      first: buttons.first,
      second: buttons.length > 1 ? buttons[1] : null,
    );
  }

  Future<void> _write(String staffId) async {
    if (_busy || !_current) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final conversation = await ref
          .read(conversationsApiProvider)
          .startWithTrainer(staffId);
      if (!mounted) return;
      final router = GoRouter.of(context);
      Navigator.of(context).pop();
      await router.push(conversationLocation(AppRole.client, conversation.id));
    } catch (_) {
      if (mounted) {
        setState(() => _error = AppLocalizations.of(context).personalLoadError);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _cancel(PersonalSession session) async {
    if (_busy || !_current) return;
    final l10n = AppLocalizations.of(context);
    final cancellation = session.cancellation;
    final effect = cancellation.effect;
    final positive = effect == 'MAKEUP_GRANTED' || effect == 'MAKEUP_RETURNED';
    final trainers = [
      for (final person in session.staff) ?person.displayName,
    ].join(', ');
    final confirmed = await showAppSheet<bool>(
      context,
      builder: (context) => Consumer(
        builder: (context, ref, _) {
          final valid =
              personalTrainingScope(ref.watch(authControllerProvider)) ==
              widget.query.scope;
          return AppSheet(
            title: valid
                ? l10n.personalCancelConfirm
                : l10n.classesProfileChanged,
            subtitle: valid
                ? [
                    classDateTime(session.startsAt),
                    if (trainers.isNotEmpty) trainers,
                    ?_participantName(),
                  ].join(' · ')
                : null,
            footer: AppButtonPair(
              first: AppButton(
                label: l10n.personalKeep,
                small: true,
                onPressed: () => Navigator.of(context).pop(),
              ),
              second: valid
                  ? AppButton(
                      label: l10n.personalCancel,
                      small: true,
                      onPressed: () => Navigator.of(context).pop(true),
                    )
                  : null,
            ),
            children: [
              if (valid)
                ConsequenceList(
                  items: [
                    Consequence(
                      icon: cancellation.late ? Icons.close : Icons.check,
                      positive: !cancellation.late,
                      title: cancellation.late
                          ? l10n.classesConsequenceLate
                          : l10n.classesConsequenceOnTime,
                      subtitle: switch (cancellation.freeCancelUntil) {
                        final deadline? =>
                          cancellation.late
                              ? l10n.personalCancelDeadlinePassed(
                                  classDateTime(deadline),
                                )
                              : l10n.personalCancelUntil(
                                  classDateTime(deadline),
                                ),
                        null => null,
                      },
                    ),
                    Consequence(
                      icon: positive
                          ? Icons.add
                          : Icons.confirmation_number_outlined,
                      title: personalEffect(l10n, effect),
                      subtitle: [
                        if (cancellation.validityDays case final days?)
                          l10n.personalMakeupValidity(days),
                        if (effect == 'MAKEUP_RETURNED')
                          l10n.personalOriginalExpiry,
                        if (cancellation.consequenceReason ==
                            'MAKEUP_LIMIT_REACHED')
                          l10n.personalMakeupLimit,
                        if (cancellation.consequenceReason ==
                            'MAKEUP_NOT_GRANTED')
                          l10n.personalMakeupUnavailable,
                        if (effect == null || effect == 'NONE')
                          ?session.passName,
                      ].join('\n').nullIfEmpty,
                    ),
                  ],
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
    final kicker = [
      session.name ?? AppLocalizations.of(context).personalTitle,
      classDateTime(session.startsAt),
    ].join(' · ');
    final reason = await showAppSheet<String>(
      context,
      builder: (_) => _DisputeSheet(query: widget.query, kicker: kicker),
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

/// "Status: Odbyty" with the attendance on the right.
class _StatusLine extends StatelessWidget {
  const _StatusLine({required this.session});

  final PersonalSession session;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final attendance = switch (session.attendance) {
      'PRESENT' => StatusDot(
        label: l10n.trainingOutcomePresent,
        tone: StatusTone.ok,
      ),
      'ABSENT' => StatusDot(
        label: l10n.trainingOutcomeAbsent,
        tone: StatusTone.warning,
      ),
      _ => null,
    };
    return Padding(
      padding: EdgeInsets.only(top: ct.spacing.m, bottom: ct.spacing.m),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.personalStatusLabel, style: ct.text.lineLabel),
                Text(personalStatus(l10n, session), style: ct.text.lineValue),
              ],
            ),
          ),
          ?attendance,
        ],
      ),
    );
  }
}

/// "Zgłoś niezgodność": what does not match, sent to the studio.
class _DisputeSheet extends ConsumerStatefulWidget {
  const _DisputeSheet({required this.query, required this.kicker});
  final PersonalSessionQuery query;
  final String kicker;
  @override
  ConsumerState<_DisputeSheet> createState() => _DisputeSheetState();
}

class _DisputeSheetState extends ConsumerState<_DisputeSheet> {
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
    final ct = context.ct;
    final valid =
        personalTrainingScope(ref.watch(authControllerProvider)) ==
        widget.query.scope;
    final missing = _submitted && _reason.text.trim().isEmpty;
    return AppSheet(
      kicker: widget.kicker,
      title: valid ? l10n.personalReport : l10n.classesProfileChanged,
      footer: AppButtonPair(
        first: AppButton(
          label: l10n.personalDisputeBack,
          small: true,
          onPressed: () => Navigator.of(context).pop(),
        ),
        second: valid
            ? AppButton(
                label: l10n.personalSend,
                small: true,
                onPressed: () {
                  setState(() => _submitted = true);
                  if (_reason.text.trim().isNotEmpty) {
                    Navigator.of(context).pop(_reason.text.trim());
                  }
                },
              )
            : null,
      ),
      children: [
        if (valid) ...[
          Padding(
            padding: EdgeInsets.only(top: ct.spacing.m, bottom: ct.spacing.s),
            child: Text(l10n.personalDisputeReason, style: ct.text.lineLabel),
          ),
          AppTextArea(
            controller: _reason,
            autofocus: true,
            maxLength: 1000,
            hint: l10n.personalDisputeHint,
            onChanged: (_) {
              if (_submitted) setState(() {});
            },
          ),
          if (missing)
            Padding(
              padding: EdgeInsets.only(top: ct.spacing.xs),
              child: Text(
                l10n.personalReasonRequired,
                style: ct.text.meta.copyWith(color: ct.colors.orangeText),
              ),
            ),
          SheetWarning(l10n.personalDisputePending),
        ],
      ],
    );
  }
}

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
