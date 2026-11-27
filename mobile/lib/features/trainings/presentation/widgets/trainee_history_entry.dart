import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainee_profile_history_api.dart';
import 'package:mobile/features/trainings/presentation/trainee_profile_history_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The entry to a trainee's change history at the top of the "Profil" tab,
/// with the last change and how many changes are new since the last visit.
class TraineeHistoryEntry extends ConsumerWidget {
  const TraineeHistoryEntry({
    required this.query,
    required this.traineeName,
    super.key,
  });

  final TraineeOverviewQuery query;
  final String traineeName;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final history = ref.watch(traineeProfileHistoryProvider(query));
    final seen = ref.watch(profileHistorySeenProvider(query)).value;
    final newest = history.value?.entries.firstOrNull;
    final unseen = history.value == null
        ? 0
        : unseenCount(history.value!, seen);
    final subtitle = switch (history) {
      AsyncData() when newest == null => l10n.traineeHistoryNoneYet,
      AsyncData() => _lastChange(l10n, newest!),
      AsyncError() => l10n.traineeHistoryUnavailable,
      _ => null,
    };
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ListTile(
        leading: Icon(Icons.history, color: theme.colorScheme.primary),
        title: Row(
          spacing: 8,
          children: [
            Flexible(child: Text(l10n.traineeHistoryTitle)),
            if (unseen > 0)
              Badge(
                label: Text(l10n.traineeHistoryNew(unseen)),
                backgroundColor: theme.colorScheme.primary,
                textColor: theme.colorScheme.onPrimary,
              ),
          ],
        ),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _open(context),
      ),
    );
  }

  void _open(BuildContext context) {
    final tabs = DefaultTabController.maybeOf(context);
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (route) => TraineeProfileHistoryScreen(
          query: query,
          traineeName: traineeName,
          onShowCard: tabs == null
              ? null
              : () {
                  Navigator.of(route).pop();
                  tabs.animateTo(2);
                },
        ),
      ),
    );
  }

  static String _lastChange(AppLocalizations l10n, ProfileHistoryEntry entry) {
    final now = DateTime.now();
    final at = entry.occurredAt;
    final day = DateTime(at.year, at.month, at.day);
    final today = DateTime(now.year, now.month, now.day);
    final time = DateFormat('HH:mm', 'pl').format(at);
    final date = day == today
        ? '${l10n.traineeHistoryToday.toLowerCase()}, $time'
        : day == today.subtract(const Duration(days: 1))
        ? '${l10n.traineeHistoryYesterday.toLowerCase()}, $time'
        : DateFormat('dd.MM.y', 'pl').format(at);
    final name = entry.actorName;
    return name == null
        ? l10n.traineeHistoryLastNoName(date)
        : l10n.traineeHistoryLast(date, name);
  }
}
