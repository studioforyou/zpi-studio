import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

String traineeDate(DateTime value) => DateFormat('dd.MM.yyyy').format(value);
String traineeTrainingTime(TraineeTraining value) =>
    DateFormat('EEE, d MMM · HH:mm', 'pl').format(value.startsAt);

String traineePassStatus(AppLocalizations l10n, TraineePass pass) =>
    switch (pass.status) {
      'PENDING_PAYMENT' => l10n.passStatusPendingPayment,
      'SCHEDULED' => l10n.passStatusScheduled,
      'SUSPENDED' => l10n.passStatusSuspended,
      'EXPIRED' => l10n.passStatusExpired,
      'CANCELLED' => l10n.passStatusCancelled,
      'ACTIVE' when pass.entriesRemaining == 0 => l10n.traineeOverviewNoEntries,
      'ACTIVE' => l10n.passStatusActive,
      _ => l10n.traineeOverviewReview,
    };

Color traineePassColor(TraineePass pass) => switch (pass.status) {
  'SUSPENDED' || 'PENDING_PAYMENT' => Colors.deepOrange,
  'EXPIRED' || 'CANCELLED' => Colors.grey,
  _ when pass.lowBalance || pass.entriesRemaining == 0 => Colors.deepOrange,
  _ when pass.eligible => Colors.green,
  _ => Colors.indigo,
};

class OverviewTag extends StatelessWidget {
  const OverviewTag(
    this.text, {
    this.color = Colors.grey,
    this.icon,
    super.key,
  });
  final String text;
  final Color color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => Container(
    decoration: BoxDecoration(
      color: color.withValues(alpha: .09),
      borderRadius: BorderRadius.circular(8),
    ),
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (icon != null) ...[
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 5),
        ],
        Flexible(
          child: Text(
            text,
            style: Theme.of(
              context,
            ).textTheme.labelMedium?.copyWith(color: color),
          ),
        ),
      ],
    ),
  );
}

class TraineePackageSummary extends StatelessWidget {
  const TraineePackageSummary(this.overview, {super.key});
  final TraineeOverview overview;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pass = overview.primaryPass;
    if (pass == null) {
      return Text(
        switch (overview.availability) {
          'NO_QUALIFIED_SERVICES' => l10n.traineeOverviewNoQualifiedServices,
          'INACTIVE' => l10n.traineesInactive,
          _ => l10n.traineeOverviewNoAvailablePass,
        },
        style: Theme.of(
          context,
        ).textTheme.bodyMedium?.copyWith(color: Colors.deepOrange),
      );
    }
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(
          pass.entriesRemaining == null
              ? l10n.passEntriesUnlimited
              : l10n.traineeOverviewEntries(pass.entriesRemaining!),
          style: Theme.of(context).textTheme.titleSmall,
        ),
        OverviewTag(
          traineePassStatus(l10n, pass),
          color: traineePassColor(pass),
        ),
        if (pass.lowBalance)
          Tooltip(
            message: l10n.traineeOverviewLowBalance,
            child: const Icon(
              Icons.warning_amber_rounded,
              color: Colors.deepOrange,
              size: 21,
            ),
          ),
        if (pass.validUntil != null)
          Text(
            l10n.traineeOverviewUntil(traineeDate(pass.validUntil!)),
            style: Theme.of(context).textTheme.bodySmall,
          ),
      ],
    );
  }
}

class TraineePassCard extends StatelessWidget {
  const TraineePassCard(this.pass, {super.key});
  final TraineePass pass;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 6,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Text(
                    pass.productName,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
                const SizedBox(width: 12),
                Flexible(
                  child: Text(
                    pass.entriesRemaining == null
                        ? l10n.passEntriesUnlimited
                        : l10n.traineeOverviewEntries(pass.entriesRemaining!),
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                    textAlign: TextAlign.end,
                  ),
                ),
              ],
            ),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                OverviewTag(
                  traineePassStatus(l10n, pass),
                  color: traineePassColor(pass),
                ),
                if (pass.validFrom != null && pass.validUntil != null)
                  Text(
                    '${traineeDate(pass.validFrom!)} – ${traineeDate(pass.validUntil!)}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                if (pass.lowBalance)
                  Tooltip(
                    message: l10n.traineeOverviewLowBalance,
                    child: const Icon(
                      Icons.warning_amber_rounded,
                      color: Colors.deepOrange,
                      size: 18,
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class TraineeTrainingLink extends StatelessWidget {
  const TraineeTrainingLink(
    this.training, {
    required this.checkedAt,
    this.attention = false,
    super.key,
  });
  final TraineeTraining training;
  final DateTime checkedAt;
  final bool attention;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final label = attention
        ? l10n.traineeOverviewAttention
        : l10n.traineeOverviewNext;
    final color = attention
        ? Colors.deepOrange
        : Theme.of(context).colorScheme.primary;
    final when = attention
        ? '$label · ${DateFormat('d MMM', 'pl').format(training.startsAt)}'
        : '${traineeTrainingTime(training)}–${DateFormat('HH:mm').format(training.endsAt)}';
    return Tooltip(
      message: label,
      child: InkWell(
        onTap: () => showTraineeTraining(context, training, checkedAt),
        borderRadius: BorderRadius.circular(6),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            children: [
              Icon(
                attention ? Icons.error_outline : Icons.event_outlined,
                color: color,
                size: 16,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  when,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: color,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Icon(Icons.chevron_right, color: color, size: 16),
            ],
          ),
        ),
      ),
    );
  }
}

class TraineeNextTraining extends StatelessWidget {
  const TraineeNextTraining(
    this.training, {
    required this.checkedAt,
    this.lowBalance = false,
    super.key,
  });

  final TraineeTraining training;
  final DateTime checkedAt;
  final bool lowBalance;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    final weekday = DateFormat('EEEE', 'pl').format(training.startsAt);
    final time = Text(
      '${DateFormat('HH:mm').format(training.startsAt)}–${DateFormat('HH:mm').format(training.endsAt)}',
      style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
    );
    final day = Text(
      '${weekday[0].toUpperCase()}${weekday.substring(1)}',
      style: theme.textTheme.bodyMedium,
    );
    final date = Container(
      constraints: const BoxConstraints(minWidth: 56),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            DateFormat('d').format(training.startsAt),
            style: theme.textTheme.headlineSmall?.copyWith(
              fontWeight: FontWeight.w700,
              color: theme.colorScheme.onPrimaryContainer,
            ),
          ),
          Text(
            DateFormat('MMM', 'pl').format(training.startsAt).toUpperCase(),
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onPrimaryContainer,
            ),
          ),
          if (training.startsAt.year != checkedAt.year)
            Text(
              '${training.startsAt.year}',
              style: theme.textTheme.labelSmall,
            ),
        ],
      ),
    );

    return Material(
      color: theme.colorScheme.primaryContainer.withValues(alpha: .22),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: theme.colorScheme.primary.withValues(alpha: .2),
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => showTraineeTraining(context, training, checkedAt),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 12,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      l10n.traineeOverviewNext,
                      style: theme.textTheme.labelLarge?.copyWith(
                        color: theme.colorScheme.primary,
                      ),
                    ),
                  ),
                  if (lowBalance)
                    Tooltip(
                      message: l10n.traineeOverviewLowBalance,
                      child: const Icon(
                        Icons.warning_amber_rounded,
                        color: Colors.deepOrange,
                        size: 21,
                      ),
                    ),
                  const Icon(Icons.chevron_right, size: 20),
                ],
              ),
              LayoutBuilder(
                builder: (context, constraints) {
                  final largeText =
                      MediaQuery.textScalerOf(context).scale(14) > 18;
                  if (largeText && constraints.maxWidth < 300) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 8,
                      children: [
                        Row(
                          children: [
                            date,
                            const SizedBox(width: 12),
                            Expanded(child: day),
                          ],
                        ),
                        time,
                      ],
                    );
                  }
                  return Row(
                    children: [
                      date,
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          spacing: 2,
                          children: [day, time],
                        ),
                      ),
                    ],
                  );
                },
              ),
              TraineeTrainingState(training, checkedAt: checkedAt),
            ],
          ),
        ),
      ),
    );
  }
}

class TraineeTrainingState extends StatelessWidget {
  const TraineeTrainingState(
    this.training, {
    required this.checkedAt,
    super.key,
  });
  final TraineeTraining training;
  final DateTime checkedAt;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final future = training.endsAt.isAfter(checkedAt);
    final cancelled = training.status == EventStatus.cancelled;
    final disputed = training.settlementStatus == 'DISPUTED';
    final (fundingText, fundingColor) = switch (training.funding) {
      'PASS_CONSUMED'
          when training.settlementStatus == 'FINAL' &&
              !disputed &&
              !training.requiresAttention =>
        (l10n.traineeOverviewSettled, Colors.green),
      'PASS_CONSUMED' => (l10n.traineeOverviewConsumed, Colors.indigo),
      'PASS_RESERVED' => (l10n.traineeOverviewReserved, Colors.indigo),
      'PASS_RETURNED' => (l10n.traineeOverviewReturned, Colors.grey),
      'MAKEUP_USED' => (l10n.traineeOverviewMakeup, Colors.indigo),
      'NO_PASS' => (l10n.traineeOverviewOutsidePass, Colors.grey),
      _ => (l10n.traineeOverviewReview, Colors.deepOrange),
    };
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        if (cancelled)
          OverviewTag(
            l10n.eventStatusCancelled,
            color: Colors.red,
            icon: Icons.event_busy,
          )
        else if (future)
          OverviewTag(
            training.startsAt.isAfter(checkedAt)
                ? l10n.traineeOverviewPlanned
                : l10n.traineeOverviewInProgress,
            color: Colors.indigo,
            icon: Icons.event_outlined,
          )
        else ...[
          _Confirmation(
            l10n.traineeOverviewTrainer,
            training.trainerConfirmedAt != null,
          ),
          _Confirmation(
            l10n.traineeOverviewParticipant,
            training.clientConfirmedAt != null,
          ),
        ],
        if (disputed)
          OverviewTag(
            l10n.traineeOverviewDisputed,
            color: Colors.red,
            icon: Icons.error_outline,
          ),
        if (training.requiresAttention &&
            !disputed &&
            training.funding != 'INCONSISTENT')
          OverviewTag(
            l10n.traineeOverviewReview,
            color: Colors.deepOrange,
            icon: Icons.error_outline,
          ),
        OverviewTag(fundingText, color: fundingColor),
      ],
    );
  }
}

class _Confirmation extends StatelessWidget {
  const _Confirmation(this.label, this.confirmed);
  final String label;
  final bool confirmed;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final meaning = confirmed
        ? l10n.traineeOverviewConfirmed
        : l10n.traineeOverviewWaiting;
    return Semantics(
      label: '$label: $meaning',
      child: Tooltip(
        message: '$label: $meaning',
        child: OverviewTag(
          label,
          color: confirmed ? Colors.green : Colors.deepOrange,
          icon: confirmed ? Icons.check_circle_outline : Icons.schedule,
        ),
      ),
    );
  }
}

class TraineeTrainingCard extends StatelessWidget {
  const TraineeTrainingCard(
    this.training, {
    required this.checkedAt,
    this.label,
    this.lowBalance = false,
    super.key,
  });
  final TraineeTraining training;
  final DateTime checkedAt;
  final String? label;
  final bool lowBalance;

  @override
  Widget build(BuildContext context) => Card(
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: () => showTraineeTraining(context, training, checkedAt),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 8,
          children: [
            if (label != null)
              Text(label!, style: Theme.of(context).textTheme.labelLarge),
            Row(
              children: [
                Expanded(
                  child: Text(
                    traineeTrainingTime(training),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                if (lowBalance &&
                    training.endsAt.isAfter(checkedAt) &&
                    training.status != EventStatus.cancelled)
                  Tooltip(
                    message: AppLocalizations.of(
                      context,
                    ).traineeOverviewLowBalance,
                    child: const Icon(
                      Icons.warning_amber_rounded,
                      color: Colors.deepOrange,
                      size: 21,
                    ),
                  ),
              ],
            ),
            Text(
              training.name ??
                  AppLocalizations.of(context).eventKindPersonalTraining,
            ),
            TraineeTrainingState(training, checkedAt: checkedAt),
          ],
        ),
      ),
    ),
  );
}

void showTraineeOverviewInfo(BuildContext context) {
  final l10n = AppLocalizations.of(context);
  showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      scrollable: true,
      title: Text(l10n.traineeOverviewInfo),
      content: Text(l10n.traineeOverviewInfoText),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(l10n.traineeOverviewClose),
        ),
      ],
    ),
  );
}

void showTraineeTraining(
  BuildContext context,
  TraineeTraining training,
  DateTime checkedAt,
) {
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 12,
          children: [
            Text(
              training.name ??
                  AppLocalizations.of(context).eventKindPersonalTraining,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Text(
              '${traineeTrainingTime(training)}–${DateFormat('HH:mm').format(training.endsAt)}',
            ),
            if (training.roomNames.isNotEmpty)
              Text(training.roomNames.join(' · ')),
            TraineeTrainingState(training, checkedAt: checkedAt),
            if (training.passName != null)
              Text(
                training.passName!,
                style: Theme.of(context).textTheme.bodySmall,
              ),
          ],
        ),
      ),
    ),
  );
}
