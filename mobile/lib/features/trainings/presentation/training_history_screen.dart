import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/features/passes/presentation/pass_usage_screen.dart';
import 'package:mobile/features/trainings/data/training_history_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Historia treningów (#281): attendance by month and pass entries, for one
/// person or the whole family.
class TrainingHistoryScreen extends ConsumerStatefulWidget {
  const TrainingHistoryScreen({
    this.today,
    this.initialTab = 0,
    this.initialProfileId,
    this.initialPassId,
    this.backLabel,
    super.key,
  });

  final DateTime? today;
  final int initialTab;
  final String? initialProfileId;
  final String? initialPassId;

  /// The screen it was opened from, for the back link; "Zajęcia" by default.
  final String? backLabel;

  @override
  ConsumerState<TrainingHistoryScreen> createState() =>
      _TrainingHistoryScreenState();
}

class _TrainingHistoryScreenState extends ConsumerState<TrainingHistoryScreen> {
  late String? _profileId = widget.initialProfileId;
  late String? _passId = widget.initialPassId;
  late int _tab = widget.initialTab;

  void _selectProfile(String? id) => setState(() {
    _profileId = id;
    _passId = null;
  });

  /// The person picker is open; it closes when the account changes.
  bool _picking = false;

  Future<void> _pickProfile(Session session) async {
    final l10n = AppLocalizations.of(context);
    _picking = true;
    final choice = await showPickerSheet<String>(
      context,
      kicker: l10n.trainingHistoryTitle,
      title: l10n.passUsagePerson,
      options: [
        PickerOption(value: '', title: l10n.passUsageAllPeople),
        for (final profile in session.profiles)
          PickerOption(
            value: profile.id,
            title: profile.displayName,
            initials: SquareAvatar.initialsOf(profile.displayName),
          ),
      ],
      selected: _profileId ?? '',
    );
    _picking = false;
    if (choice == null || !mounted) return;
    final auth = ref.read(authControllerProvider);
    if (passUsageAccount(auth) != session.accountId) return;
    if (choice.isNotEmpty &&
        !(auth as Authenticated).session.profiles.any((p) => p.id == choice)) {
      return;
    }
    _selectProfile(choice.isEmpty ? null : choice);
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(authControllerProvider, (previous, next) {
      final selectedProfile = _profileId;
      final selectionRemoved =
          selectedProfile != null &&
          (next is! Authenticated ||
              !next.session.profiles.any(
                (profile) => profile.id == selectedProfile,
              ));
      final accountChanged =
          previous != null &&
          passUsageAccount(previous) != passUsageAccount(next);
      if (accountChanged && _picking) {
        // The open picker lists the people of the previous account.
        Navigator.of(context, rootNavigator: true).maybePop();
      }
      if (accountChanged || (previous != null && selectionRemoved)) {
        _selectProfile(null);
      }
    });
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final state = ref.watch(authControllerProvider);
    final accountId = passUsageAccount(state);
    final header = SubPageHeader(
      backLabel: widget.backLabel ?? l10n.navClasses,
      title: l10n.trainingHistoryTitle,
      actions: [
        HeaderIconButton(
          icon: Icons.info_outline,
          tooltip: l10n.trainingHistoryHelpTitle,
          onPressed: () => showAppSheet<void>(
            context,
            builder: (_) => AppSheet(
              title: l10n.trainingHistoryHelpTitle,
              titleSmall: true,
              children: [SheetWarning(l10n.trainingHistoryHelp)],
            ),
          ),
        ),
      ],
    );
    if (accountId == null) {
      return Scaffold(
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            header,
            Expanded(child: ClassMessage(l10n.classesNoProfile)),
          ],
        ),
      );
    }
    final session = (state as Authenticated).session;
    final person = session.profiles
        .where((profile) => profile.id == _profileId)
        .firstOrNull;
    return Scaffold(
      key: ValueKey(accountId),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          header,
          if (session.profiles.length > 1)
            Padding(
              padding: EdgeInsets.fromLTRB(
                ct.spacing.page,
                ct.spacing.m,
                ct.spacing.page,
                0,
              ),
              child: PickerField(
                title: person?.displayName ?? l10n.passUsageAllPeople,
                onTap: () => _pickProfile(session),
              ),
            ),
          SizedBox(height: ct.spacing.s),
          Expanded(
            child: _tab == 0
                ? _MonthlyHistory(
                    session: session,
                    profileId: _profileId,
                    today: widget.today ?? DateTime.now(),
                    tabs: _tabs(l10n),
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _tabs(l10n),
                      Expanded(
                        child: PassUsageScreen(
                          accountId: accountId,
                          initialProfileId: _profileId,
                          initialPassId: _passId,
                          onProfileChanged: _selectProfile,
                          embedded: true,
                        ),
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  Widget _tabs(AppLocalizations l10n) => AppTabs(
    labels: [l10n.trainingHistoryClassesTab, l10n.trainingHistoryEntriesTab],
    selected: _tab,
    onChanged: (tab) => setState(() => _tab = tab),
  );
}

class _MonthlyHistory extends ConsumerStatefulWidget {
  const _MonthlyHistory({
    required this.session,
    required this.today,
    required this.tabs,
    this.profileId,
  });

  final Session session;
  final DateTime today;
  final Widget tabs;
  final String? profileId;

  @override
  ConsumerState<_MonthlyHistory> createState() => _MonthlyHistoryState();
}

class _MonthlyHistoryState extends ConsumerState<_MonthlyHistory>
    with WidgetsBindingObserver {
  late DateTime _month = DateTime(widget.today.year, widget.today.month);

  AccountTrainingHistoryQuery get _query => (
    accountId: widget.session.accountId,
    profileId: widget.profileId,
    window: (from: _month, to: DateTime(_month.year, _month.month + 1)),
  );

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
    if (state == AppLifecycleState.resumed &&
        passUsageAccount(ref.read(authControllerProvider)) ==
            widget.session.accountId) {
      ref.invalidate(accountTrainingHistoryProvider(_query));
    }
  }

  bool get _isCurrentMonth =>
      _month.year == widget.today.year && _month.month == widget.today.month;

  void _shift(int months) =>
      setState(() => _month = DateTime(_month.year, _month.month + months));

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final provider = accountTrainingHistoryProvider(_query);
    final history = ref.watch(provider);
    final entries = history.value;
    // The whole family shows each person's name on their rows.
    final names =
        widget.profileId == null && widget.session.profiles.length > 1;
    return RefreshIndicator(
      onRefresh: () => ref.refresh(provider.future),
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(
            child: MonthNav(
              month: _month,
              previousTooltip: l10n.trainingHistoryPreviousMonth,
              nextTooltip: l10n.trainingHistoryNextMonth,
              onPrevious: () => _shift(-1),
              onNext: _isCurrentMonth ? null : () => _shift(1),
            ),
          ),
          SliverToBoxAdapter(
            child: _HistorySummary([
              for (final entry in entries ?? const <ParticipantTraining>[])
                entry.training,
            ]),
          ),
          SliverToBoxAdapter(child: widget.tabs),
          ...history.when(
            skipLoadingOnRefresh: false,
            skipLoadingOnReload: false,
            loading: () => [
              const SliverFillRemaining(
                hasScrollBody: false,
                child: Center(child: CircularProgressIndicator()),
              ),
            ],
            error: (_, _) => [
              SliverToBoxAdapter(
                child: ClassesLoadError(
                  onRetry: () => ref.invalidate(provider),
                ),
              ),
            ],
            data: (entries) => [
              if (entries.isEmpty)
                SliverToBoxAdapter(
                  child: ClassMessage(
                    l10n.trainingHistoryEmpty,
                    icon: Icons.history,
                  ),
                )
              else
                SliverList.list(
                  children: [
                    for (final entry in entries)
                      _HistoryRow(
                        entry.training,
                        names ? entry.profileName : null,
                      ),
                  ],
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Four numbers in hairline cells: present, absent, cancelled, attendance.
class _HistorySummary extends StatelessWidget {
  const _HistorySummary(this.entries);

  final List<TrainingHistoryEntry> entries;

  int _count(Set<TrainingOutcome> outcomes) =>
      entries.where((entry) => outcomes.contains(entry.outcome)).length;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final present = _count({TrainingOutcome.present});
    final absent = _count({TrainingOutcome.absent});
    final cancelled = _count({
      TrainingOutcome.cancelled,
      TrainingOutcome.lateCancelled,
      TrainingOutcome.classCancelled,
    });
    final rate = present + absent == 0
        ? null
        : (present * 100 / (present + absent)).round();
    Widget cell(String value, String label, {bool last = false}) => Expanded(
      child: Container(
        padding: EdgeInsets.fromLTRB(
          ct.spacing.page,
          ct.spacing.m,
          ct.spacing.xs,
          ct.spacing.m,
        ),
        decoration: BoxDecoration(
          border: last ? null : Border(right: ct.hairline),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(value, style: ct.text.statNumber),
            SizedBox(height: ct.spacing.xs),
            Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: ct.text.caption,
            ),
          ],
        ),
      ),
    );
    return Semantics(
      label: rate == null ? null : l10n.trainingHistoryAttendanceRate(rate),
      child: DecoratedBox(
        decoration: BoxDecoration(
          border: Border(top: ct.hairline, bottom: ct.hairline),
        ),
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              cell('$present', l10n.historyStatPresent),
              cell('$absent', l10n.historyStatAbsent),
              cell('$cancelled', l10n.historyStatCancelled),
              cell(
                rate == null ? '–' : '$rate%',
                l10n.historyStatRate,
                last: true,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// One training: the date on the left, what it was in the middle and how it
/// ended on the right.
class _HistoryRow extends StatelessWidget {
  const _HistoryRow(this.entry, this.participantName);

  final TrainingHistoryEntry entry;

  /// Whose training it was, in the family view.
  final String? participantName;

  static final _date = DateFormat('d.MM');
  static final _weekday = DateFormat('EEE', 'pl');
  static final _time = DateFormat('HH:mm');

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final (label, tone) = _outcome(l10n, entry.outcome);
    final cancelled = switch (entry.outcome) {
      TrainingOutcome.cancelled ||
      TrainingOutcome.lateCancelled ||
      TrainingOutcome.classCancelled => true,
      _ => false,
    };
    final teachers = entry.staff
        .where((person) => person.isLead && person.displayName != null)
        .map((person) => person.displayName!)
        .join(', ');
    final note =
        entry.cancellationEffect == 'MAKEUP_GRANTED' ||
            entry.cancellationEffect == 'MAKEUP_RETURNED'
        ? (entry.cancellationEffect == 'MAKEUP_GRANTED'
              ? l10n.personalMakeupGranted
              : l10n.personalMakeupReturned)
        : entry.outcome == TrainingOutcome.lateCancelled &&
              entry.entryReturned == false
        ? l10n.trainingHistoryEntryNotReturned
        : null;
    return Container(
      padding: EdgeInsets.fromLTRB(sp.page, sp.m, sp.page, sp.m),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: ct.sizes.timeColumn,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(_date.format(entry.startsAt), style: ct.text.historyDate),
                SizedBox(height: sp.xxs),
                Text(
                  '${_weekday.format(entry.startsAt)} · '
                  '${_time.format(entry.startsAt)}',
                  style: ct.text.caption,
                ),
              ],
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.name ?? l10n.classesTitle,
                  style: ct.text.rowTitle.copyWith(
                    color: cancelled ? c.muted : c.ink,
                  ),
                ),
                SizedBox(height: sp.xxs),
                Text(
                  [
                    ?participantName,
                    if (teachers.isNotEmpty) teachers,
                    ...entry.roomNames,
                  ].join(' · '),
                  style: ct.text.meta.copyWith(color: c.muted),
                ),
                if (note != null)
                  Text(note, style: ct.text.meta.copyWith(color: c.muted)),
              ],
            ),
          ),
          SizedBox(width: sp.s),
          Padding(
            padding: EdgeInsets.only(top: sp.xxs),
            child: ConstrainedBox(
              constraints: BoxConstraints(maxWidth: ct.sizes.timeColumn * 1.6),
              child: StatusDot(label: label, tone: tone),
            ),
          ),
        ],
      ),
    );
  }

  static (String, StatusTone) _outcome(
    AppLocalizations l10n,
    TrainingOutcome outcome,
  ) => switch (outcome) {
    TrainingOutcome.present => (l10n.trainingOutcomePresent, StatusTone.ok),
    TrainingOutcome.absent => (l10n.trainingOutcomeAbsent, StatusTone.warning),
    TrainingOutcome.unmarked => (
      l10n.trainingOutcomeUnmarkedShort,
      StatusTone.scheduled,
    ),
    TrainingOutcome.cancelled => (
      l10n.trainingOutcomeCancelledShort,
      StatusTone.off,
    ),
    TrainingOutcome.lateCancelled => (
      l10n.trainingOutcomeLateCancelledShort,
      StatusTone.warning,
    ),
    TrainingOutcome.classCancelled => (
      l10n.trainingOutcomeClassCancelledShort,
      StatusTone.off,
    ),
  };
}
