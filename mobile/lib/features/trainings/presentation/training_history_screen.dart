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

/// One place for attendance and pass accounting, with pass-card shortcuts.
class TrainingHistoryScreen extends ConsumerStatefulWidget {
  const TrainingHistoryScreen({
    this.today,
    this.initialTab = 0,
    this.initialProfileId,
    this.initialPassId,
    super.key,
  });

  final DateTime? today;
  final int initialTab;
  final String? initialProfileId;
  final String? initialPassId;

  @override
  ConsumerState<TrainingHistoryScreen> createState() =>
      _TrainingHistoryScreenState();
}

class _TrainingHistoryScreenState extends ConsumerState<TrainingHistoryScreen> {
  late String? _profileId = widget.initialProfileId;
  late String? _passId = widget.initialPassId;

  void _selectProfile(String? id) => setState(() {
    _profileId = id;
    _passId = null;
  });

  Widget _participantLabel(String accountId, String? profileId) => Consumer(
    builder: (context, ref, _) {
      final auth = ref.watch(authControllerProvider);
      if (passUsageAccount(auth) != accountId) return const SizedBox.shrink();
      if (profileId == null) {
        return Text(AppLocalizations.of(context).passUsageAllPeople);
      }
      final profile = (auth as Authenticated).session.profiles
          .where((profile) => profile.id == profileId)
          .firstOrNull;
      return profile == null
          ? const SizedBox.shrink()
          : Text(profile.displayName);
    },
  );

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
      if (previous != null &&
          (passUsageAccount(previous) != passUsageAccount(next) ||
              selectionRemoved)) {
        _selectProfile(null);
      }
    });
    final l10n = AppLocalizations.of(context);
    final state = ref.watch(authControllerProvider);
    final accountId = passUsageAccount(state);
    if (accountId == null) {
      return Scaffold(
        appBar: AppBar(title: Text(l10n.trainingHistoryTitle)),
        body: ClassMessage(l10n.classesNoProfile),
      );
    }
    final session = (state as Authenticated).session;
    return DefaultTabController(
      key: ValueKey(accountId),
      length: 2,
      initialIndex: widget.initialTab,
      child: Scaffold(
        appBar: AppBar(
          title: Text(l10n.trainingHistoryTitle),
          actions: [
            IconButton(
              tooltip: l10n.trainingHistoryHelpTitle,
              icon: const Icon(Icons.info_outline),
              onPressed: () => showModalBottomSheet<void>(
                context: context,
                showDragHandle: true,
                builder: (context) => SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                    child: Text(l10n.trainingHistoryHelp),
                  ),
                ),
              ),
            ),
          ],
          bottom: TabBar(
            tabs: [
              Tab(text: l10n.trainingHistoryClassesTab),
              Tab(text: l10n.trainingHistoryEntriesTab),
            ],
          ),
        ),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: PopupMenuButton<String>(
                tooltip: l10n.passUsagePerson,
                initialValue: _profileId ?? '',
                onSelected: (value) {
                  final auth = ref.read(authControllerProvider);
                  if (passUsageAccount(auth) == accountId &&
                      (value.isEmpty ||
                          (auth as Authenticated).session.profiles.any(
                            (profile) => profile.id == value,
                          ))) {
                    _selectProfile(value.isEmpty ? null : value);
                  }
                },
                itemBuilder: (context) => [
                  PopupMenuItem(
                    value: '',
                    child: _participantLabel(accountId, null),
                  ),
                  for (final profile in session.profiles)
                    PopupMenuItem(
                      value: profile.id,
                      child: _participantLabel(accountId, profile.id),
                    ),
                ],
                child: Chip(
                  avatar: const Icon(Icons.people_outline, size: 18),
                  label: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Flexible(
                        child: Text(
                          session.profiles
                                  .where((profile) => profile.id == _profileId)
                                  .firstOrNull
                                  ?.displayName ??
                              l10n.passUsageAllPeople,
                        ),
                      ),
                      const Icon(Icons.arrow_drop_down, size: 18),
                    ],
                  ),
                ),
              ),
            ),
            Expanded(
              child: TabBarView(
                children: [
                  _MonthlyHistory(
                    session: session,
                    profileId: _profileId,
                    today: widget.today ?? DateTime.now(),
                  ),
                  PassUsageScreen(
                    accountId: accountId,
                    initialProfileId: _profileId,
                    initialPassId: _passId,
                    onProfileChanged: _selectProfile,
                    embedded: true,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MonthlyHistory extends ConsumerStatefulWidget {
  const _MonthlyHistory({
    required this.session,
    required this.today,
    this.profileId,
  });

  final Session session;
  final DateTime today;
  final String? profileId;

  @override
  ConsumerState<_MonthlyHistory> createState() => _MonthlyHistoryState();
}

class _MonthlyHistoryState extends ConsumerState<_MonthlyHistory>
    with
        WidgetsBindingObserver,
        AutomaticKeepAliveClientMixin<_MonthlyHistory> {
  late DateTime _month = DateTime(widget.today.year, widget.today.month);

  @override
  bool get wantKeepAlive => true;

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
    super.build(context);
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = accountTrainingHistoryProvider(_query);
    final monthLabel = DateFormat('LLLL yyyy', 'pl').format(_month);
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Row(
            children: [
              IconButton(
                icon: const Icon(Icons.chevron_left),
                tooltip: l10n.trainingHistoryPreviousMonth,
                onPressed: () => _shift(-1),
              ),
              Expanded(
                child: Text(
                  '${monthLabel[0].toUpperCase()}${monthLabel.substring(1)}',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.titleMedium,
                ),
              ),
              IconButton(
                icon: const Icon(Icons.chevron_right),
                tooltip: l10n.trainingHistoryNextMonth,
                onPressed: _isCurrentMonth ? null : () => _shift(1),
              ),
            ],
          ),
        ),
        Expanded(
          child: ref
              .watch(provider)
              .when(
                skipLoadingOnRefresh: false,
                skipLoadingOnReload: false,
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => Center(
                  child: ClassesLoadError(
                    onRetry: () => ref.invalidate(provider),
                  ),
                ),
                data: (entries) => RefreshIndicator(
                  onRefresh: () => ref.refresh(provider.future),
                  child: ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                    children: [
                      if (entries.isEmpty)
                        ClassMessage(
                          l10n.trainingHistoryEmpty,
                          icon: Icons.history,
                        )
                      else ...[
                        _HistorySummary([
                          for (final entry in entries) entry.training,
                        ]),
                        for (final entry in entries)
                          _HistoryTile(entry.training, entry.profileName),
                      ],
                    ],
                  ),
                ),
              ),
        ),
      ],
    );
  }
}

class _HistorySummary extends StatelessWidget {
  const _HistorySummary(this.entries);

  final List<TrainingHistoryEntry> entries;

  int _count(Set<TrainingOutcome> outcomes) =>
      entries.where((entry) => outcomes.contains(entry.outcome)).length;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final present = _count({TrainingOutcome.present});
    final absent = _count({TrainingOutcome.absent});
    final cancelled = _count({
      TrainingOutcome.cancelled,
      TrainingOutcome.lateCancelled,
      TrainingOutcome.classCancelled,
    });
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          spacing: 12,
          children: [
            Row(
              children: [
                _Stat(present, l10n.trainingHistoryPresent),
                _Stat(absent, l10n.trainingHistoryAbsent),
                _Stat(cancelled, l10n.trainingHistoryCancelled),
              ],
            ),
            if (present + absent > 0)
              Text(
                l10n.trainingHistoryAttendanceRate(
                  (present * 100 / (present + absent)).round(),
                ),
                style: theme.textTheme.labelLarge,
              ),
          ],
        ),
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat(this.value, this.label);

  final int value;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Expanded(
      child: Column(
        children: [
          Text('$value', style: theme.textTheme.headlineSmall),
          Text(
            label,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

class _HistoryTile extends StatelessWidget {
  const _HistoryTile(this.entry, this.participantName);

  final TrainingHistoryEntry entry;
  final String participantName;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final (icon, label, color) = _outcome(l10n, theme, entry.outcome);
    final crossedOut = switch (entry.outcome) {
      TrainingOutcome.cancelled ||
      TrainingOutcome.lateCancelled ||
      TrainingOutcome.classCancelled => true,
      _ => false,
    };
    final teachers = entry.staff
        .where((person) => person.isLead && person.displayName != null)
        .map((person) => person.displayName!)
        .join(', ');
    final time = DateFormat('HH:mm');
    return Card(
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              width: 6,
              color: serviceColor(entry.calendarColor, theme.colorScheme),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 6,
                  children: [
                    Text(
                      participantName,
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: theme.colorScheme.primary,
                      ),
                    ),
                    Text(
                      '${classDate(entry.startsAt)}, '
                      '${time.format(entry.startsAt)}–'
                      '${time.format(entry.endsAt)}',
                      style: theme.textTheme.labelLarge,
                    ),
                    Text(
                      entry.name ?? l10n.classesTitle,
                      style: theme.textTheme.titleMedium?.copyWith(
                        decoration: crossedOut
                            ? TextDecoration.lineThrough
                            : null,
                      ),
                    ),
                    if (teachers.isNotEmpty || entry.roomNames.isNotEmpty)
                      Text(
                        [
                          if (teachers.isNotEmpty) teachers,
                          ...entry.roomNames,
                        ].join(' · '),
                        style: theme.textTheme.bodySmall,
                      ),
                    Row(
                      children: [
                        Icon(icon, size: 16, color: color),
                        const SizedBox(width: 6),
                        Flexible(
                          child: Text(
                            label,
                            style: theme.textTheme.labelMedium?.copyWith(
                              color: color,
                            ),
                          ),
                        ),
                      ],
                    ),
                    if (entry.outcome == TrainingOutcome.lateCancelled &&
                        entry.entryReturned == false)
                      Text(
                        l10n.trainingHistoryEntryNotReturned,
                        style: theme.textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  static (IconData, String, Color) _outcome(
    AppLocalizations l10n,
    ThemeData theme,
    TrainingOutcome outcome,
  ) => switch (outcome) {
    TrainingOutcome.present => (
      Icons.check_circle_outline,
      l10n.trainingOutcomePresent,
      Colors.green.shade700,
    ),
    TrainingOutcome.absent => (
      Icons.cancel_outlined,
      l10n.trainingOutcomeAbsent,
      theme.colorScheme.error,
    ),
    TrainingOutcome.unmarked => (
      Icons.help_outline,
      l10n.trainingOutcomeUnmarked,
      theme.colorScheme.onSurfaceVariant,
    ),
    TrainingOutcome.cancelled => (
      Icons.event_busy_outlined,
      l10n.trainingOutcomeCancelled,
      theme.colorScheme.onSurfaceVariant,
    ),
    TrainingOutcome.lateCancelled => (
      Icons.event_busy_outlined,
      l10n.trainingOutcomeLateCancelled,
      Colors.orange.shade800,
    ),
    TrainingOutcome.classCancelled => (
      Icons.block,
      l10n.trainingOutcomeClassCancelled,
      theme.colorScheme.error,
    ),
  };
}
