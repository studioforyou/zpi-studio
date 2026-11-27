import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainee_profile_history_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The change history of a trainee's profile: personal details, the card's
/// goals and experience, and health notes (only with access to health data).
class TraineeProfileHistoryScreen extends ConsumerStatefulWidget {
  const TraineeProfileHistoryScreen({
    required this.query,
    required this.traineeName,
    this.onShowCard,
    super.key,
  });

  final TraineeOverviewQuery query;
  final String traineeName;

  /// Opens the trainee's card, where the health notes are; hidden when null.
  final VoidCallback? onShowCard;

  @override
  ConsumerState<TraineeProfileHistoryScreen> createState() =>
      _TraineeProfileHistoryScreenState();
}

class _TraineeProfileHistoryScreenState
    extends ConsumerState<TraineeProfileHistoryScreen> {
  ProfileHistoryGroup? _group;

  @override
  void initState() {
    super.initState();
    // Opening the history marks every change on it as seen.
    ref.listenManual(traineeProfileHistoryProvider(widget.query), (_, next) {
      final newest = next.value?.entries.firstOrNull;
      if (newest == null) return;
      Future.microtask(() {
        if (!mounted) return;
        ref
            .read(profileHistorySeenProvider(widget.query).notifier)
            .markSeen(newest.id);
      });
    }, fireImmediately: true);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final provider = traineeProfileHistoryProvider(widget.query);

    Future<void> refresh() async {
      ref.invalidate(provider);
      try {
        await ref.read(provider.future);
      } catch (_) {
        // The failure is rendered below with a retry action.
      }
    }

    return Scaffold(
      appBar: AppBar(title: Text(l10n.traineeHistoryTitle)),
      body: ref
          .watch(provider)
          .when(
            skipLoadingOnRefresh: true,
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (error, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  spacing: 12,
                  children: [
                    Text(
                      error is ApiException && error.statusCode == 404
                          ? l10n.traineeProfileUnavailable
                          : l10n.traineeHistoryLoadError,
                      textAlign: TextAlign.center,
                    ),
                    OutlinedButton(
                      onPressed: refresh,
                      child: Text(l10n.traineesRetry),
                    ),
                  ],
                ),
              ),
            ),
            data: (history) => RefreshIndicator(
              onRefresh: refresh,
              child: _content(context, l10n, history),
            ),
          ),
    );
  }

  Widget _content(
    BuildContext context,
    AppLocalizations l10n,
    ProfileHistory history,
  ) {
    final theme = Theme.of(context);
    final health = history.healthAccess == HealthAccess.granted;
    final group = !health && _group == ProfileHistoryGroup.health
        ? null
        : _group;
    final entries = [
      for (final entry in history.entries)
        if (group == null || entry.group == group) entry,
    ];
    final days = <DateTime, List<ProfileHistoryEntry>>{};
    for (final entry in entries) {
      final at = entry.occurredAt;
      days
          .putIfAbsent(DateTime(at.year, at.month, at.day), () => [])
          .add(entry);
    }
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
          child: Text(
            widget.traineeName,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          child: Row(
            spacing: 8,
            children: [
              _chip(l10n.traineeHistoryAll, null, group),
              _chip(
                l10n.traineeHistoryPersonal,
                ProfileHistoryGroup.personal,
                group,
              ),
              _chip(l10n.traineeHistoryCard, ProfileHistoryGroup.card, group),
              if (health)
                _chip(
                  l10n.traineeHistoryHealth,
                  ProfileHistoryGroup.health,
                  group,
                ),
            ],
          ),
        ),
        if (!health)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: _Hint(
              history.healthAccess == HealthAccess.notShared
                  ? l10n.traineeHistoryHealthNotShared
                  : l10n.traineeHistoryHealthNoPermission,
            ),
          ),
        if (history.entries.isEmpty)
          _Empty(
            title: l10n.traineeHistoryEmptyTitle,
            text: l10n.traineeHistoryEmpty,
          )
        else if (entries.isEmpty)
          Padding(
            padding: const EdgeInsets.all(32),
            child: Text(
              l10n.traineeHistoryFilterEmpty,
              textAlign: TextAlign.center,
            ),
          )
        else
          for (final day in days.entries) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
              child: Text(
                _dayLabel(l10n, day.key),
                style: theme.textTheme.labelLarge?.copyWith(
                  color: theme.colorScheme.primary,
                ),
              ),
            ),
            for (final (index, entry) in day.value.indexed)
              _EntryTile(
                entry: entry,
                last: index == day.value.length - 1,
                onShowCard: widget.onShowCard,
              ),
          ],
        if (history.entries.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(32, 20, 32, 0),
            child: Text(
              history.hasMore
                  ? l10n.traineeHistoryMore(TraineeProfileHistoryApi.maxEntries)
                  : l10n.traineeHistorySince,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
      ],
    );
  }

  Widget _chip(
    String label,
    ProfileHistoryGroup? value,
    ProfileHistoryGroup? selected,
  ) => ChoiceChip(
    label: Text(label),
    selected: value == selected,
    showCheckmark: false,
    onSelected: (_) => setState(() => _group = value),
  );

  static String _dayLabel(AppLocalizations l10n, DateTime day) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    if (day == today) return l10n.traineeHistoryToday;
    if (day == today.subtract(const Duration(days: 1))) {
      return l10n.traineeHistoryYesterday;
    }
    return DateFormat('dd.MM.y', 'pl').format(day);
  }
}

class _EntryTile extends StatelessWidget {
  const _EntryTile({
    required this.entry,
    required this.last,
    required this.onShowCard,
  });

  final ProfileHistoryEntry entry;
  final bool last;
  final VoidCallback? onShowCard;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final health = entry.group == ProfileHistoryGroup.health;
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: scheme.onSurfaceVariant,
    );
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 68,
            child: Column(
              children: [
                const SizedBox(height: 8),
                CircleAvatar(
                  radius: 18,
                  backgroundColor: health
                      ? scheme.errorContainer
                      : scheme.secondaryContainer,
                  child: Icon(
                    _icon(entry),
                    size: 20,
                    color: health
                        ? scheme.onErrorContainer
                        : scheme.onSecondaryContainer,
                  ),
                ),
                if (!last)
                  Expanded(
                    child: Container(
                      width: 2,
                      margin: const EdgeInsets.only(top: 4),
                      color: scheme.outlineVariant,
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(0, 8, 16, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 4,
                children: [
                  Text(_title(l10n, entry), style: theme.textTheme.titleSmall),
                  ..._details(context, l10n),
                  Text(_byline(l10n, entry), style: muted),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _details(BuildContext context, AppLocalizations l10n) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    switch (entry.group) {
      case ProfileHistoryGroup.health:
        final kind = entry.healthNoteKind;
        return [
          Text.rich(
            TextSpan(
              children: [
                if (kind != null) TextSpan(text: healthKindLabel(l10n, kind)),
                if (entry.action == 'HEALTH_NOTE_ARCHIVED')
                  TextSpan(
                    text: ' · ${l10n.traineeHistoryHealthArchivedNote}',
                    style: muted,
                  ),
              ],
            ),
          ),
          if (onShowCard != null && entry.action == 'HEALTH_NOTE_ADDED')
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                style: TextButton.styleFrom(
                  padding: EdgeInsets.zero,
                  minimumSize: const Size(0, 32),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                onPressed: onShowCard,
                child: Text(l10n.traineeHistoryShowCard),
              ),
            ),
        ];
      case ProfileHistoryGroup.card:
        return [
          for (final change in entry.changes)
            _TextChange(
              label: entry.changes.length > 1
                  ? _fieldLabel(l10n, change.field)
                  : null,
              change: change,
            ),
        ];
      case ProfileHistoryGroup.personal:
        return [
          for (final change in entry.changes)
            _FieldChangeLine(
              label: _fieldLabel(l10n, change.field),
              oldValue: _value(change.field, change.oldValue),
              newValue: _value(change.field, change.newValue),
            ),
        ];
    }
  }

  static String? _value(String field, String? value) {
    if (value == null || field != 'birthDate') return value;
    final date = DateTime.tryParse(value);
    return date == null ? value : DateFormat('dd.MM.y', 'pl').format(date);
  }

  static IconData _icon(ProfileHistoryEntry entry) => switch (entry.group) {
    ProfileHistoryGroup.health => Icons.healing,
    ProfileHistoryGroup.card =>
      entry.changes.every((change) => change.field == 'sportExperience')
          ? Icons.directions_run
          : Icons.flag,
    ProfileHistoryGroup.personal =>
      _onlyEmergency(entry) ? Icons.contact_phone : Icons.person,
  };

  static bool _onlyEmergency(ProfileHistoryEntry entry) => entry.changes.every(
    (change) =>
        change.field == 'emergencyContactName' ||
        change.field == 'emergencyPhone',
  );

  static String _title(AppLocalizations l10n, ProfileHistoryEntry entry) {
    final fields = {for (final change in entry.changes) change.field};
    return switch (entry.group) {
      ProfileHistoryGroup.health =>
        entry.action == 'HEALTH_NOTE_ARCHIVED'
            ? l10n.traineeHistoryHealthArchived
            : l10n.traineeHistoryHealthAdded,
      ProfileHistoryGroup.card =>
        fields.length > 1
            ? l10n.traineeHistoryCardChanged
            : fields.contains('sportExperience')
            ? l10n.traineeHistoryExperienceChanged
            : l10n.traineeHistoryGoalsChanged,
      ProfileHistoryGroup.personal =>
        _onlyEmergency(entry)
            ? l10n.traineeHistoryEmergencyChanged
            : fields.length == 1 && fields.contains('phone')
            ? l10n.traineeHistoryContactChanged
            : l10n.traineeHistoryPersonalChanged,
    };
  }

  static String _fieldLabel(AppLocalizations l10n, String field) =>
      switch (field) {
        'firstName' => l10n.traineeHistoryFieldFirstName,
        'lastName' => l10n.traineeHistoryFieldLastName,
        'birthDate' => l10n.traineeHistoryFieldBirthDate,
        'phone' => l10n.traineeHistoryFieldPhone,
        'emergencyContactName' => l10n.traineeHistoryFieldEmergencyName,
        'emergencyPhone' => l10n.traineeHistoryFieldEmergencyPhone,
        'goals' => l10n.traineeCardGoals,
        'sportExperience' => l10n.traineeCardExperience,
        _ => field,
      };

  static String _byline(AppLocalizations l10n, ProfileHistoryEntry entry) {
    final time = DateFormat('HH:mm', 'pl').format(entry.occurredAt);
    final role = entry.actorRole;
    if (role == null) return l10n.traineeHistoryBySystem(time);
    return l10n.traineeHistoryBy(
      time,
      entry.actorName ?? l10n.traineeProfileMissing,
      roleLabel(l10n, role),
    );
  }
}

/// A short field: the old value crossed out, then the new one.
class _FieldChangeLine extends StatelessWidget {
  const _FieldChangeLine({
    required this.label,
    required this.oldValue,
    required this.newValue,
  });

  final String label;
  final String? oldValue;
  final String? newValue;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Text.rich(
      TextSpan(
        style: theme.textTheme.bodyMedium,
        children: [
          TextSpan(
            text: '$label: ',
            style: TextStyle(color: muted),
          ),
          if (oldValue != null) ...[
            TextSpan(
              text: oldValue,
              style: TextStyle(
                color: muted,
                decoration: TextDecoration.lineThrough,
              ),
            ),
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Icon(Icons.arrow_forward, size: 16, color: muted),
              ),
            ),
          ],
          TextSpan(
            text: newValue ?? l10n.traineeHistoryCleared,
            style: newValue == null ? TextStyle(color: muted) : null,
          ),
        ],
      ),
    );
  }
}

/// A long text of the card: the new text, then what it was before.
class _TextChange extends StatelessWidget {
  const _TextChange({required this.label, required this.change});

  final String? label;
  final ProfileFieldChange change;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    final oldValue = change.oldValue;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 4,
        children: [
          if (label != null) Text(label!, style: muted),
          Text(change.newValue ?? l10n.traineeHistoryTextCleared),
          Text(
            oldValue == null
                ? l10n.traineeHistoryPreviousEmpty
                : l10n.traineeHistoryPrevious(oldValue),
            style: muted,
          ),
        ],
      ),
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 8,
        children: [
          Icon(
            Icons.lock_outline,
            size: 18,
            color: theme.colorScheme.onSurfaceVariant,
          ),
          Expanded(child: Text(text, style: theme.textTheme.bodySmall)),
        ],
      ),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.title, required this.text});

  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(32, 64, 32, 0),
      child: Column(
        spacing: 8,
        children: [
          Icon(Icons.history, size: 48, color: theme.colorScheme.outline),
          Text(title, style: theme.textTheme.titleMedium),
          Text(text, textAlign: TextAlign.center),
        ],
      ),
    );
  }
}

/// Polish label of a health note kind, as on the card.
String healthKindLabel(AppLocalizations l10n, HealthNoteKind kind) =>
    switch (kind) {
      HealthNoteKind.injury => l10n.traineeCardHealthKindInjury,
      HealthNoteKind.condition => l10n.traineeCardHealthKindCondition,
      HealthNoteKind.contraindication =>
        l10n.traineeCardHealthKindContraindication,
      HealthNoteKind.other => l10n.traineeCardHealthKindOther,
    };

/// Who the author of a change is to the trainee, in lowercase.
String roleLabel(AppLocalizations l10n, ProfileHistoryRole role) =>
    switch (role) {
      ProfileHistoryRole.client => l10n.traineeHistoryRoleClient,
      ProfileHistoryRole.guardian => l10n.traineeHistoryRoleGuardian,
      ProfileHistoryRole.trainer => l10n.traineeHistoryRoleTrainer,
      ProfileHistoryRole.admin => l10n.traineeHistoryRoleAdmin,
    };
