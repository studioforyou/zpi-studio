import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/schedule/data/time_off_api.dart';
import 'package:mobile/features/schedule/presentation/availability_screen.dart';
import 'package:mobile/features/schedule/presentation/time_off_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// "Moje urlopy": every request of the trainer, waiting ones first. Tapping a
/// request that is still ahead pops its first day for the calendar to show.
class TimeOffListScreen extends ConsumerWidget {
  const TimeOffListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.timeOffListTitle)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) =>
          ref
              .watch(ownTimeOffProvider(session.accountId))
              .when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      spacing: 16,
                      children: [
                        Text(
                          l10n.timeOffListLoadError,
                          textAlign: TextAlign.center,
                        ),
                        OutlinedButton.icon(
                          onPressed: () => ref.invalidate(
                            ownTimeOffProvider(session.accountId),
                          ),
                          icon: const Icon(Icons.refresh),
                          label: Text(l10n.availabilityRetry),
                        ),
                      ],
                    ),
                  ),
                ),
                data: (entries) => _TimeOffList(
                  entries: entries,
                  now: ref.watch(availabilityNowProvider),
                ),
              ),
      },
    );
  }
}

class _TimeOffList extends StatelessWidget {
  const _TimeOffList({required this.entries, required this.now});

  final List<TimeOff> entries;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    if (entries.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(l10n.timeOffListEmpty, textAlign: TextAlign.center),
        ),
      );
    }

    bool ahead(TimeOff entry) => entry.endsAt.isAfter(now);
    final pending = [
      for (final entry in entries)
        if (entry.status == TimeOffStatus.requested && ahead(entry)) entry,
    ]..sort((a, b) => a.startsAt.compareTo(b.startsAt));
    final upcoming = [
      for (final entry in entries)
        if (entry.status == TimeOffStatus.approved && ahead(entry)) entry,
    ]..sort((a, b) => a.startsAt.compareTo(b.startsAt));
    final past = [
      for (final entry in entries)
        if (!pending.contains(entry) && !upcoming.contains(entry)) entry,
    ]..sort((a, b) => b.startsAt.compareTo(a.startsAt));

    Widget header(String text) => Padding(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
      child: Text(
        text,
        style: theme.textTheme.titleSmall?.copyWith(
          color: theme.colorScheme.primary,
        ),
      ),
    );

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
      children: [
        if (pending.isNotEmpty) ...[
          header(l10n.timeOffListPending),
          for (final entry in pending) _TimeOffCard(entry: entry, now: now),
        ],
        if (upcoming.isNotEmpty) ...[
          header(l10n.timeOffListUpcoming),
          for (final entry in upcoming) _TimeOffCard(entry: entry, now: now),
        ],
        if (past.isNotEmpty) ...[
          header(l10n.timeOffListPast),
          for (final entry in past)
            _TimeOffCard(entry: entry, now: now, faded: true),
        ],
        if (pending.isNotEmpty || upcoming.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 16),
            child: Text(
              l10n.timeOffListHint,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
      ],
    );
  }
}

class _TimeOffCard extends StatelessWidget {
  const _TimeOffCard({
    required this.entry,
    required this.now,
    this.faded = false,
  });

  final TimeOff entry;
  final DateTime now;
  final bool faded;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final note = entry.decisionNote;
    final reason = entry.reason;
    final content = Padding(
      padding: const EdgeInsets.all(16),
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
                  timeOffKindLabel(l10n, entry.kind),
                  style: theme.textTheme.titleMedium,
                ),
              ),
              TimeOffStatusChip(status: entry.status, short: true),
            ],
          ),
          Text(
            formatTimeOffPeriod(l10n, entry.startsAt, entry.endsAt),
            style: theme.textTheme.bodyMedium,
          ),
          if (reason != null && reason.isNotEmpty)
            Text(
              reason,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
          if (note != null && note.isNotEmpty)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(top: 6),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                l10n.timeOffStudioNote(note),
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
        ],
      ),
    );
    return Card(
      key: Key('time-off-card-${entry.id}'),
      margin: const EdgeInsets.only(bottom: 12),
      clipBehavior: Clip.antiAlias,
      child: faded
          ? Opacity(opacity: 0.7, child: content)
          : InkWell(
              onTap: () => Navigator.of(context).pop(
                entry.startsAt.isAfter(now)
                    ? DateUtils.dateOnly(entry.startsAt)
                    : DateUtils.dateOnly(now),
              ),
              child: content,
            ),
    );
  }
}
