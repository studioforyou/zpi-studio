import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/trainings/data/trainee_makeups_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/trainee_overview_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Credits expiring within this many days are highlighted.
const makeupExpiryWarningDays = 14;

/// Whole calendar days from [today] to the day the credit expires; 0 = today.
int makeupDaysLeft(MakeupCredit credit, DateTime today) {
  final expires = DateTime(
    credit.expiresAt.year,
    credit.expiresAt.month,
    credit.expiresAt.day,
  );
  return expires
      .difference(DateTime(today.year, today.month, today.day))
      .inDays;
}

String makeupReason(AppLocalizations l10n, MakeupReason reason) =>
    switch (reason) {
      MakeupReason.cancelledByStudio => l10n.traineeMakeupsReasonStudio,
      MakeupReason.cancelledInTime => l10n.traineeMakeupsReasonInTime,
      MakeupReason.other => l10n.traineeMakeupsReasonOther,
    };

/// "Za: Calisthenics, 01.11.2026 · odwołane w terminie", or who granted it.
String makeupOriginLine(AppLocalizations l10n, MakeupCredit credit) {
  final origin = credit.origin;
  if (origin == null) return l10n.traineeMakeupsGrantedByStudio;
  final title = origin.title ?? l10n.traineeMakeupsUnknownClass;
  return '${l10n.traineeMakeupsFor(title, traineeDate(origin.startsAt))}'
      ' · ${makeupReason(l10n, origin.reason)}';
}

/// The trainee's make-up credits at the top of the packages tab. Read-only.
class TraineeMakeupsSection extends ConsumerWidget {
  const TraineeMakeupsSection({required this.query, super.key});
  final TraineeOverviewQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final result = ref.watch(traineeMakeupsProvider(query));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 8,
      children: [
        Text(
          l10n.traineeMakeupsTitle,
          style: Theme.of(context).textTheme.titleSmall,
        ),
        ...result.when(
          skipLoadingOnRefresh: true,
          loading: () => const [
            Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Center(child: CircularProgressIndicator()),
            ),
          ],
          error: (_, _) => [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Expanded(child: Text(l10n.traineeMakeupsLoadError)),
                    TextButton(
                      onPressed: () =>
                          ref.invalidate(traineeMakeupsProvider(query)),
                      child: Text(l10n.traineeMakeupsRetry),
                    ),
                  ],
                ),
              ),
            ),
          ],
          data: (makeups) => [
            TraineeMakeupsSummary(makeups),
            for (final limit in makeups.limits.where((limit) => limit.reached))
              _LimitReached(limit),
            if (makeups.items.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text(
                  l10n.traineeMakeupsEmpty,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            for (final credit in makeups.items)
              TraineeMakeupCard(credit, makeups: makeups),
          ],
        ),
      ],
    );
  }
}

class TraineeMakeupsSummary extends StatelessWidget {
  const TraineeMakeupsSummary(this.makeups, {super.key});
  final TraineeMakeups makeups;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final nearest = makeups.items.firstOrNull;
    final daysLeft = nearest == null
        ? null
        : makeupDaysLeft(nearest, makeups.checkedOn);
    final soon = daysLeft != null && daysLeft <= makeupExpiryWarningDays;
    return Card(
      color: theme.colorScheme.primaryContainer.withValues(alpha: .35),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          spacing: 12,
          children: [
            Icon(Icons.event_repeat, color: theme.colorScheme.primary),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 2,
                children: [
                  Text(
                    l10n.traineeMakeupsCount(makeups.totalCount),
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if (nearest != null)
                    Text(
                      daysLeft == 0
                          ? l10n.traineeMakeupsNearestToday
                          : l10n.traineeMakeupsNearest(
                              traineeDate(nearest.expiresAt),
                            ),
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: soon ? Colors.deepOrange : null,
                      ),
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

class _LimitReached extends StatelessWidget {
  const _LimitReached(this.limit);
  final MakeupLimit limit;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Colors.deepOrange.withValues(alpha: .08),
      borderRadius: BorderRadius.circular(8),
    ),
    child: Row(
      spacing: 8,
      children: [
        const Icon(Icons.block, size: 20, color: Colors.deepOrange),
        Expanded(
          child: Text(
            AppLocalizations.of(context).traineeMakeupsLimitReached(
              limit.productName,
              limit.granted,
              limit.max,
            ),
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ),
      ],
    ),
  );
}

class TraineeMakeupCard extends StatelessWidget {
  const TraineeMakeupCard(this.credit, {required this.makeups, super.key});
  final MakeupCredit credit;
  final TraineeMakeups makeups;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final daysLeft = makeupDaysLeft(credit, makeups.checkedOn);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => showTraineeMakeup(context, credit, makeups),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 4,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 8,
                children: [
                  Expanded(
                    child: Text(
                      l10n.traineeMakeupsUntil(traineeDate(credit.expiresAt)),
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                  if (daysLeft <= makeupExpiryWarningDays)
                    OverviewTag(
                      daysLeft <= 0
                          ? l10n.traineeMakeupsExpiresToday
                          : l10n.traineeMakeupsExpiresIn(daysLeft),
                      color: Colors.deepOrange,
                      icon: Icons.schedule,
                    ),
                ],
              ),
              Text(credit.productName, style: theme.textTheme.bodyMedium),
              Text(
                makeupOriginLine(l10n, credit),
                style: theme.textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

void showTraineeMakeup(
  BuildContext context,
  MakeupCredit credit,
  TraineeMakeups makeups,
) {
  final limit = makeups.limitFor(credit.sourcePassId);
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (context) {
      final l10n = AppLocalizations.of(context);
      final theme = Theme.of(context);
      final origin = credit.origin;
      final daysLeft = makeupDaysLeft(credit, makeups.checkedOn);
      Widget row(String label, String value, {Color? color}) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 2,
        children: [
          Text(label, style: theme.textTheme.labelMedium),
          Text(value, style: theme.textTheme.bodyLarge?.copyWith(color: color)),
        ],
      );
      return SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 14,
            children: [
              Text(
                l10n.traineeMakeupsDetailsTitle,
                style: theme.textTheme.titleLarge,
              ),
              row(
                l10n.traineeMakeupsValidUntil,
                '${traineeDate(credit.expiresAt)}, ${DateFormat('HH:mm').format(credit.expiresAt)}',
                color: daysLeft <= makeupExpiryWarningDays
                    ? Colors.deepOrange
                    : null,
              ),
              row(l10n.traineeMakeupsPass, credit.productName),
              if (origin != null) ...[
                row(
                  l10n.traineeMakeupsOrigin,
                  '${origin.title ?? l10n.traineeMakeupsUnknownClass}, '
                  '${traineeDate(origin.startsAt)} ${DateFormat('HH:mm').format(origin.startsAt)}',
                ),
                row(
                  l10n.traineeMakeupsReason,
                  makeupReason(l10n, origin.reason),
                ),
              ] else
                row(
                  l10n.traineeMakeupsOrigin,
                  l10n.traineeMakeupsGrantedByStudio,
                ),
              row(l10n.traineeMakeupsGrantedAt, traineeDate(credit.grantedAt)),
              row(
                l10n.traineeMakeupsPolicy,
                credit.policyName ?? l10n.traineeMakeupsNoPolicy,
              ),
              if (limit != null)
                Text(
                  l10n.traineeMakeupsPassLimit(limit.granted, limit.max),
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: limit.reached ? Colors.deepOrange : null,
                  ),
                ),
              Text(
                l10n.traineeMakeupsServices,
                style: theme.textTheme.labelMedium,
              ),
              if (credit.services.isEmpty)
                Text(l10n.traineeMakeupsNoServices)
              else
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final service in credit.services)
                      Chip(label: Text(service.name)),
                  ],
                ),
              Text(
                l10n.traineeMakeupsReadOnly,
                style: theme.textTheme.bodySmall,
              ),
            ],
          ),
        ),
      );
    },
  );
}
