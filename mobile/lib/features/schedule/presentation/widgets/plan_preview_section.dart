import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/presentation/plan_messages.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The result of checking a planned class: conflicts that block saving, in
/// red, and warnings that do not, in amber. A conflicting date of a
/// recurring class can be left out of the series.
class PlanPreviewSection extends StatelessWidget {
  const PlanPreviewSection({
    super.key,
    required this.complete,
    required this.loading,
    required this.preview,
    required this.error,
    required this.recurring,
    required this.onToggleExcluded,
    required this.onRetry,
  });

  /// Whether the form describes a whole plan that can be checked.
  final bool complete;
  final bool loading;
  final PlanPreview? preview;
  final Object? error;
  final bool recurring;

  /// Leaves a date out of the series or brings it back.
  final ValueChanged<DateTime>? onToggleExcluded;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final preview = this.preview;
    final error = this.error;

    final Widget content;
    if (!complete) {
      content = Text(l10n.addClassPreviewIncomplete);
    } else if (loading) {
      content = Row(
        spacing: 12,
        children: [
          const SizedBox.square(
            dimension: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          Text(l10n.addClassPreviewLoading),
        ],
      );
    } else if (error != null) {
      content = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 8,
        children: [
          Text(
            planErrorMessage(error, l10n),
            style: TextStyle(color: theme.colorScheme.error),
          ),
          TextButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
            label: Text(l10n.scheduleRetryButton),
          ),
        ],
      );
    } else if (preview != null) {
      content = _result(context, preview, l10n);
    } else {
      content = const SizedBox();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 8,
      children: [
        Text(l10n.addClassPreviewTitle, style: theme.textTheme.titleMedium),
        content,
      ],
    );
  }

  Widget _result(
    BuildContext context,
    PlanPreview preview,
    AppLocalizations l10n,
  ) {
    final occurrences = preview.occurrences;
    final withIssues = [
      for (final occurrence in occurrences)
        if (occurrence.issues.isNotEmpty || occurrence.excluded) occurrence,
    ];
    final planned = occurrences.where((o) => !o.excluded).length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 8,
      children: [
        if (occurrences.isEmpty)
          _Message(
            icon: Icons.error_outline,
            color: Theme.of(context).colorScheme.error,
            text: l10n.addClassErrorNoOccurrences,
          )
        else if (preview.conflictCount == 0)
          _Message(
            icon: Icons.check_circle_outline,
            color: Colors.green.shade700,
            text: recurring
                ? l10n.addClassNoConflictsSeries(planned)
                : l10n.addClassNoConflicts,
          )
        else
          _Message(
            icon: Icons.error_outline,
            color: Theme.of(context).colorScheme.error,
            text: recurring
                ? l10n.addClassConflictsSeries(preview.conflictCount)
                : l10n.addClassConflicts,
          ),
        for (final warning in preview.warnings) _IssueRow(issue: warning),
        for (final occurrence in withIssues)
          _OccurrenceCard(
            occurrence: occurrence,
            recurring: recurring,
            onToggleExcluded: onToggleExcluded,
          ),
      ],
    );
  }
}

/// One date of the plan with its problems.
class _OccurrenceCard extends StatelessWidget {
  const _OccurrenceCard({
    required this.occurrence,
    required this.recurring,
    required this.onToggleExcluded,
  });

  final PlannedOccurrence occurrence;
  final bool recurring;
  final ValueChanged<DateTime>? onToggleExcluded;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final time = DateFormat('HH:mm');
    final day = DateFormat('EEEE, d MMMM', 'pl').format(occurrence.startsAt);
    final title =
        '$day · ${time.format(occurrence.startsAt)}–'
        '${time.format(occurrence.endsAt)}';
    final toggle = onToggleExcluded;

    return Card(
      key: Key('addClassOccurrence-${formatDate(occurrence.date)}'),
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 8,
          children: [
            Text(
              title,
              style: TextStyle(
                fontWeight: FontWeight.w600,
                decoration: occurrence.excluded
                    ? TextDecoration.lineThrough
                    : null,
              ),
            ),
            if (occurrence.excluded)
              Text(l10n.addClassExcluded)
            else
              for (final issue in occurrence.issues) _IssueRow(issue: issue),
            if (recurring &&
                (occurrence.excluded || occurrence.hasBlockingIssue))
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: toggle == null
                      ? null
                      : () => toggle(occurrence.date),
                  child: Text(
                    occurrence.excluded
                        ? l10n.addClassIncludeDate
                        : l10n.addClassExcludeDate,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// A problem: red when it blocks saving, amber otherwise.
class _IssueRow extends StatelessWidget {
  const _IssueRow({required this.issue});

  final PlanIssue issue;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return _Message(
      icon: issue.blocking ? Icons.block : Icons.warning_amber_rounded,
      color: issue.blocking
          ? Theme.of(context).colorScheme.error
          : Colors.amber.shade800,
      text: _describe(issue, l10n),
    );
  }

  static String _describe(PlanIssue issue, AppLocalizations l10n) {
    final time = DateFormat('HH:mm');
    final window = issue.startsAt != null && issue.endsAt != null
        ? '${time.format(issue.startsAt!)}–${time.format(issue.endsAt!)}'
        : '';
    final title = issue.eventTitle ?? '';
    return switch (issue.type) {
      IssueType.roomBusy => l10n.planIssueRoomBusy(title, window),
      IssueType.trainerBusy => l10n.planIssueTrainerBusy(title, window),
      IssueType.trainerOnLeave => l10n.planIssueTrainerOnLeave,
      IssueType.trainerLeaveRequested => l10n.planIssueLeaveRequested,
      IssueType.outsideWorkingHours => l10n.planIssueOutsideHours,
      IssueType.notQualified => l10n.planIssueNotQualified,
      IssueType.unknown => l10n.planIssueUnknown,
    };
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.color, required this.text});

  final IconData icon;
  final Color color;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 8,
      children: [
        Icon(icon, color: color, size: 20),
        Expanded(
          child: Text(text, style: TextStyle(color: color)),
        ),
      ],
    );
  }
}
