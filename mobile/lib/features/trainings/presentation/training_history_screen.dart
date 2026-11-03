import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/training_history_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The selected participant's past trainings, month by month, with their
/// attendance and cancellations.
class TrainingHistoryScreen extends ConsumerWidget {
  const TrainingHistoryScreen({this.today, super.key});

  final DateTime? today;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final state = ref.watch(authControllerProvider);
    final session = clientSession(state);
    final scope = bookingScope(state);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.trainingHistoryTitle)),
      body: scope == null || session == null
          ? ClassMessage(l10n.classesNoProfile)
          : _MonthlyHistory(
              key: ValueKey(scope),
              scope: scope,
              session: session,
              today: today ?? DateTime.now(),
            ),
    );
  }
}

class _MonthlyHistory extends ConsumerStatefulWidget {
  const _MonthlyHistory({
    required this.scope,
    required this.session,
    required this.today,
    super.key,
  });

  final BookingScope scope;
  final Session session;
  final DateTime today;

  @override
  ConsumerState<_MonthlyHistory> createState() => _MonthlyHistoryState();
}

class _MonthlyHistoryState extends ConsumerState<_MonthlyHistory> {
  late DateTime _month = DateTime(widget.today.year, widget.today.month);

  bool get _isCurrentMonth =>
      _month.year == widget.today.year && _month.month == widget.today.month;

  void _shift(int months) =>
      setState(() => _month = DateTime(_month.year, _month.month + months));

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = trainingHistoryProvider((
      scope: widget.scope,
      window: (from: _month, to: DateTime(_month.year, _month.month + 1)),
    ));
    final profileName = widget.session.profiles.length > 1
        ? widget.session.profiles
              .where((profile) => profile.id == widget.scope.profileId)
              .firstOrNull
              ?.displayName
        : null;
    final monthLabel = DateFormat('LLLL yyyy', 'pl').format(_month);
    return Column(
      children: [
        if (profileName != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: Text(l10n.passesProfile(profileName)),
          ),
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
                        _HistorySummary(entries),
                        for (final entry in entries) _HistoryTile(entry),
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
  const _HistoryTile(this.entry);

  final TrainingHistoryEntry entry;

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
                    if (teachers.isNotEmpty)
                      Text(teachers, style: theme.textTheme.bodySmall),
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
