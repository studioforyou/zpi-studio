import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/trainings/data/trainee_card_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Edits the goals and sport experience on a trainee's card. Pops with the
/// saved card. The card is shared by the trainee's trainers, so a change
/// someone saved in the meantime is shown under the fields before the
/// trainer saves again.
class TraineeCardEditScreen extends ConsumerStatefulWidget {
  const TraineeCardEditScreen({
    required this.query,
    required this.card,
    super.key,
  });

  final TraineeOverviewQuery query;
  final TraineeCard card;

  @override
  ConsumerState<TraineeCardEditScreen> createState() =>
      _TraineeCardEditScreenState();
}

class _TraineeCardEditScreenState extends ConsumerState<TraineeCardEditScreen> {
  late final _goals = TextEditingController(text: widget.card.goals ?? '');
  late final _experience = TextEditingController(
    text: widget.card.sportExperience ?? '',
  );

  /// The saved card the change is based on; a newer one after a conflict.
  late TraineeCard _base = widget.card;
  bool _conflict = false;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _goals.dispose();
    _experience.dispose();
    super.dispose();
  }

  bool get _changed =>
      _goals.text.trim() != (_base.goals ?? '') ||
      _experience.text.trim() != (_base.sportExperience ?? '');

  Future<void> _save() async {
    if (_saving || !_changed) return;
    FocusScope.of(context).unfocus();
    final l10n = AppLocalizations.of(context);
    setState(() {
      _saving = true;
      _error = null;
    });
    final api = ref.read(traineeCardApiProvider);
    try {
      final saved = await api.update(
        widget.query.customerId,
        goals: _goals.text,
        sportExperience: _experience.text,
        version: _base.version,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l10n.traineeCardSaved)));
      Navigator.of(context).pop(saved);
    } on TraineeCardException catch (e) {
      if (e.isVersionConflict) {
        await _loadNewer(api, l10n);
      } else if (mounted) {
        setState(
          () => _error = e.isUnavailable
              ? l10n.traineeCardUnavailable
              : l10n.traineeCardSaveError,
        );
      }
    } catch (_) {
      if (mounted) setState(() => _error = l10n.traineeCardSaveError);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// Takes what another trainer saved into the fields the trainer has not
  /// changed; a changed field keeps the trainer's text and shows the saved
  /// one under it. The next save is based on the newer card.
  Future<void> _loadNewer(TraineeCardApi api, AppLocalizations l10n) async {
    try {
      final newer = await api.fetch(widget.query.customerId);
      if (!mounted) return;
      setState(() {
        if (_goals.text.trim() == (_base.goals ?? '')) {
          _goals.text = newer.goals ?? '';
        }
        if (_experience.text.trim() == (_base.sportExperience ?? '')) {
          _experience.text = newer.sportExperience ?? '';
        }
        _base = newer;
        _conflict = true;
      });
    } on TraineeCardException catch (e) {
      if (!mounted) return;
      setState(
        () => _error = e.isUnavailable
            ? l10n.traineeCardUnavailable
            : l10n.traineeCardSaveError,
      );
    } catch (_) {
      if (mounted) setState(() => _error = l10n.traineeCardSaveError);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.traineeCardEditTitle)),
      body: ListenableBuilder(
        listenable: Listenable.merge([_goals, _experience]),
        builder: (context, _) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (_conflict)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: _Notice(
                  text: switch (_base.updatedBy) {
                    final name? => l10n.traineeCardConflictBy(name),
                    null => l10n.traineeCardConflict,
                  },
                ),
              ),
            _field(
              controller: _goals,
              label: l10n.traineeCardGoals,
              hint: l10n.traineeCardGoalsHint,
              saved: _base.goals,
            ),
            const SizedBox(height: 16),
            _field(
              controller: _experience,
              label: l10n.traineeCardExperience,
              hint: l10n.traineeCardExperienceHint,
              saved: _base.sportExperience,
            ),
            if (_error case final error?)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  error,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
              ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _saving || !_changed ? null : _save,
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

  Widget _field({
    required TextEditingController controller,
    required String label,
    required String hint,
    required String? saved,
  }) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: controller,
          enabled: !_saving,
          maxLength: traineeCardTextMaxLength,
          minLines: 3,
          maxLines: 8,
          keyboardType: TextInputType.multiline,
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(
            labelText: label,
            hintText: hint,
            alignLabelWithHint: true,
            border: const OutlineInputBorder(),
          ),
        ),
        // After a conflict: what is saved now, to compare with the own text.
        if (_conflict && controller.text.trim() != (saved ?? ''))
          SelectableText(
            saved == null
                ? l10n.traineeCardConflictSavedEmpty
                : l10n.traineeCardConflictSaved(saved),
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
      ],
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: scheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 12,
          children: [
            Icon(Icons.sync_problem, color: scheme.onErrorContainer),
            Expanded(
              child: Text(
                text,
                style: TextStyle(color: scheme.onErrorContainer),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
