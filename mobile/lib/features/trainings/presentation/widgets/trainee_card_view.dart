import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/trainee_card_edit_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The "Karta" tab of a trainee: goals, sport experience and the health
/// restrictions the trainee shared with the trainer.
class TraineeCardView extends ConsumerWidget {
  const TraineeCardView({required this.query, super.key});

  final TraineeOverviewQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = traineeCardProvider(query);
    Future<void> refresh() async {
      ref.invalidate(provider);
      try {
        await ref.read(provider.future);
      } catch (_) {
        // The failure is rendered below with a retry action.
      }
    }

    return ref
        .watch(provider)
        .when(
          skipLoadingOnRefresh: true,
          skipLoadingOnReload: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                spacing: 12,
                children: [
                  Text(
                    error is TraineeCardException && error.isUnavailable
                        ? l10n.traineeCardUnavailable
                        : l10n.traineeCardLoadError,
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
          data: (card) => RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              children: [
                _GoalsSection(query: query, card: card),
                const SizedBox(height: 16),
                _HealthSection(query: query, card: card),
                const SizedBox(height: 12),
                Text(
                  l10n.traineeCardShared,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        );
  }
}

String _date(DateTime date) => DateFormat('dd.MM.y', 'pl').format(date);

class _GoalsSection extends ConsumerWidget {
  const _GoalsSection({required this.query, required this.card});

  final TraineeOverviewQuery query;
  final TraineeCard card;

  Future<void> _edit(BuildContext context, WidgetRef ref) async {
    await Navigator.of(context).push<TraineeCard>(
      MaterialPageRoute(
        builder: (_) => TraineeCardEditScreen(query: query, card: card),
      ),
    );
    // Also after leaving without saving: the edit may have shown a newer card.
    if (context.mounted) ref.invalidate(traineeCardProvider(query));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    final empty = card.goals == null && card.sportExperience == null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 8, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 10,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.traineeCardGoalsSection,
                    style: theme.textTheme.titleSmall,
                  ),
                ),
                IconButton(
                  onPressed: () => _edit(context, ref),
                  tooltip: l10n.traineeCardEdit,
                  icon: const Icon(Icons.edit_outlined),
                ),
              ],
            ),
            if (empty)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Text(l10n.traineeCardEmpty),
              )
            else ...[
              _Text(label: l10n.traineeCardGoals, value: card.goals),
              _Text(
                label: l10n.traineeCardExperience,
                value: card.sportExperience,
              ),
            ],
            if (card.updatedAt case final updatedAt?)
              Text(switch (card.updatedBy) {
                final name? => l10n.traineeCardUpdated(_date(updatedAt), name),
                null => l10n.traineeCardUpdatedAt(_date(updatedAt)),
              }, style: muted),
          ],
        ),
      ),
    );
  }
}

class _Text extends StatelessWidget {
  const _Text({required this.label, required this.value});

  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 2,
        children: [
          Text(label, style: theme.textTheme.bodySmall?.copyWith(color: muted)),
          SelectableText(
            value ?? l10n.traineeCardNotFilled,
            style: value == null
                ? theme.textTheme.bodyMedium?.copyWith(color: muted)
                : theme.textTheme.bodyMedium,
          ),
        ],
      ),
    );
  }
}

class _HealthSection extends ConsumerWidget {
  const _HealthSection({required this.query, required this.card});

  final TraineeOverviewQuery query;
  final TraineeCard card;

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final added = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _AddHealthNoteSheet(customerId: query.customerId),
    );
    if (!context.mounted) return;
    ref.invalidate(traineeCardProvider(query));
    if (added == true) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppLocalizations.of(context).traineeCardHealthAdded),
        ),
      );
    }
  }

  Future<void> _archive(
    BuildContext context,
    WidgetRef ref,
    HealthNote note,
  ) async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.traineeCardHealthArchiveTitle),
        content: Text(l10n.traineeCardHealthArchiveText),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l10n.traineeCardCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(l10n.traineeCardHealthArchive),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref
          .read(traineeCardApiProvider)
          .archiveHealthNote(query.customerId, note.id);
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.traineeCardHealthArchiveDone)),
      );
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(_healthError(l10n, e))));
    }
    if (context.mounted) ref.invalidate(traineeCardProvider(query));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final granted = card.healthAccess == HealthAccess.granted;
    final current = card.currentHealthNotes;
    final archived = card.archivedHealthNotes;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.traineeCardHealth,
                    style: theme.textTheme.titleSmall,
                  ),
                ),
                if (granted)
                  TextButton.icon(
                    onPressed: () => _add(context, ref),
                    icon: const Icon(Icons.add),
                    label: Text(l10n.traineeCardHealthAdd),
                  )
                else
                  // Keeps the header as tall as the other section's.
                  const SizedBox(height: 48),
              ],
            ),
            ...switch (card.healthAccess) {
              HealthAccess.notShared => [
                _Locked(text: l10n.traineeCardHealthNotShared),
              ],
              HealthAccess.noPermission => [
                _Locked(text: l10n.traineeCardHealthNoPermission),
              ],
              HealthAccess.granted => [
                if (current.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(right: 8, bottom: 8),
                    child: Text(l10n.traineeCardHealthEmpty),
                  ),
                for (final note in current)
                  _HealthNoteTile(
                    note: note,
                    onArchive: () => _archive(context, ref, note),
                  ),
                if (archived.isNotEmpty)
                  ExpansionTile(
                    tilePadding: const EdgeInsets.only(right: 8),
                    childrenPadding: EdgeInsets.zero,
                    shape: const Border(),
                    collapsedShape: const Border(),
                    title: Text(
                      l10n.traineeCardHealthArchivedSection(archived.length),
                      style: theme.textTheme.bodyMedium,
                    ),
                    children: [
                      for (final note in archived) _HealthNoteTile(note: note),
                    ],
                  ),
              ],
            },
          ],
        ),
      ),
    );
  }
}

String _healthError(AppLocalizations l10n, Object error) => switch (error) {
  TraineeCardException(isHealthAccessDenied: true) =>
    l10n.traineeCardHealthAccessLost,
  TraineeCardException(isUnavailable: true) => l10n.traineeCardUnavailable,
  _ => l10n.traineeCardHealthSaveError,
};

String _kindLabel(AppLocalizations l10n, HealthNoteKind kind) => switch (kind) {
  HealthNoteKind.injury => l10n.traineeCardHealthKindInjury,
  HealthNoteKind.condition => l10n.traineeCardHealthKindCondition,
  HealthNoteKind.contraindication => l10n.traineeCardHealthKindContraindication,
  HealthNoteKind.other => l10n.traineeCardHealthKindOther,
};

IconData _kindIcon(HealthNoteKind kind) => switch (kind) {
  HealthNoteKind.injury => Icons.personal_injury_outlined,
  HealthNoteKind.condition => Icons.monitor_heart_outlined,
  HealthNoteKind.contraindication => Icons.do_not_disturb_on_outlined,
  HealthNoteKind.other => Icons.info_outline,
};

class _Locked extends StatelessWidget {
  const _Locked({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(right: 8, bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 12,
        children: [
          Icon(Icons.lock_outline, color: muted, size: 20),
          Expanded(
            child: Text(
              text,
              style: theme.textTheme.bodyMedium?.copyWith(color: muted),
            ),
          ),
        ],
      ),
    );
  }
}

class _HealthNoteTile extends StatelessWidget {
  const _HealthNoteTile({required this.note, this.onArchive});

  final HealthNote note;

  /// `null` for an archived note.
  final VoidCallback? onArchive;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final meta = theme.textTheme.bodySmall?.copyWith(color: muted);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2, right: 12),
            child: Icon(
              _kindIcon(note.kind),
              size: 20,
              color: note.isArchived ? muted : theme.colorScheme.error,
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: 2,
              children: [
                Text(
                  _kindLabel(l10n, note.kind),
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: note.isArchived ? muted : null,
                  ),
                ),
                SelectableText(
                  note.description,
                  style: note.isArchived
                      ? theme.textTheme.bodyMedium?.copyWith(color: muted)
                      : theme.textTheme.bodyMedium,
                ),
                Text(switch (note.reportedBy) {
                  final name? => l10n.traineeCardHealthReported(
                    _date(note.reportedAt),
                    name,
                  ),
                  null => l10n.traineeCardHealthReportedAt(
                    _date(note.reportedAt),
                  ),
                }, style: meta),
                if (note.archivedAt case final archivedAt?)
                  Text(switch (note.archivedBy) {
                    final name? => l10n.traineeCardHealthArchivedBy(
                      _date(archivedAt),
                      name,
                    ),
                    null => l10n.traineeCardHealthArchivedAt(_date(archivedAt)),
                  }, style: meta),
              ],
            ),
          ),
          if (onArchive != null)
            IconButton(
              onPressed: onArchive,
              tooltip: l10n.traineeCardHealthArchive,
              icon: const Icon(Icons.archive_outlined),
            )
          else
            const SizedBox(width: 8),
        ],
      ),
    );
  }
}

class _AddHealthNoteSheet extends ConsumerStatefulWidget {
  const _AddHealthNoteSheet({required this.customerId});

  final String customerId;

  @override
  ConsumerState<_AddHealthNoteSheet> createState() =>
      _AddHealthNoteSheetState();
}

class _AddHealthNoteSheetState extends ConsumerState<_AddHealthNoteSheet> {
  final _description = TextEditingController();
  HealthNoteKind _kind = HealthNoteKind.injury;
  bool _saving = false;
  bool _missingDescription = false;
  String? _error;

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving) return;
    final l10n = AppLocalizations.of(context);
    final description = _description.text.trim();
    if (description.isEmpty) {
      setState(() => _missingDescription = true);
      return;
    }
    setState(() {
      _saving = true;
      _missingDescription = false;
      _error = null;
    });
    try {
      await ref
          .read(traineeCardApiProvider)
          .addHealthNote(
            widget.customerId,
            kind: _kind,
            description: description,
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() => _error = _healthError(l10n, e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        0,
        24,
        24 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            Text(
              l10n.traineeCardHealthAddTitle,
              style: theme.textTheme.titleLarge,
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: 8,
              children: [
                Text(
                  l10n.traineeCardHealthKind,
                  style: theme.textTheme.titleSmall,
                ),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final kind in HealthNoteKind.values)
                      ChoiceChip(
                        // The icon stays; the selection shows as the fill.
                        showCheckmark: false,
                        avatar: Icon(_kindIcon(kind), size: 18),
                        label: Text(_kindLabel(l10n, kind)),
                        selected: _kind == kind,
                        onSelected: _saving
                            ? null
                            : (_) => setState(() => _kind = kind),
                      ),
                  ],
                ),
              ],
            ),
            TextField(
              controller: _description,
              enabled: !_saving,
              autofocus: true,
              maxLength: healthNoteMaxLength,
              minLines: 2,
              maxLines: 5,
              keyboardType: TextInputType.multiline,
              textCapitalization: TextCapitalization.sentences,
              onChanged: (_) {
                if (_missingDescription) {
                  setState(() => _missingDescription = false);
                }
              },
              decoration: InputDecoration(
                labelText: l10n.traineeCardHealthDescription,
                hintText: l10n.traineeCardHealthDescriptionHint,
                alignLabelWithHint: true,
                border: const OutlineInputBorder(),
                errorText: _missingDescription
                    ? l10n.traineeCardHealthDescriptionRequired
                    : null,
              ),
            ),
            Text(
              l10n.traineeCardHealthAddInfo,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            if (_error case final error?)
              Text(
                error,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Text(l10n.traineeCardSave),
            ),
          ],
        ),
      ),
    );
  }
}
