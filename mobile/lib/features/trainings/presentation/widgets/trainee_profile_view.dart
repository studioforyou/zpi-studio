import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainee_profile_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The "Profil" tab of a trainee: personal and contact details.
class TraineeProfileView extends ConsumerWidget {
  const TraineeProfileView({required this.query, super.key});

  final TraineeOverviewQuery query;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final provider = traineeProfileProvider(query);
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
                    error is ApiException && error.statusCode == 404
                        ? l10n.traineeProfileUnavailable
                        : l10n.traineeProfileLoadError,
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
          data: (profile) => RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              children: [
                _Section(
                  title: l10n.traineeProfileDetails,
                  rows: [
                    _Row(
                      l10n.traineeProfileType,
                      profile.isChild
                          ? l10n.traineesChild
                          : l10n.traineeProfileAdult,
                    ),
                    _Row(l10n.traineeProfileBirthDate, _birth(l10n, profile)),
                    _Row(l10n.traineeProfilePhone, profile.phone),
                    _Row(
                      l10n.traineeProfileCooperationSince,
                      DateFormat('dd.MM.y', 'pl').format(profile.assignedAt),
                    ),
                  ],
                ),
                if (profile.isChild) ...[
                  const SizedBox(height: 16),
                  _Section(
                    title: l10n.traineeProfileGuardians,
                    emptyText: l10n.traineeProfileNoGuardians,
                    rows: [
                      for (final guardian in profile.guardians)
                        _Row(
                          '${guardian.displayName} · '
                          '${_relation(l10n, guardian.relation)}',
                          guardian.phone,
                        ),
                    ],
                  ),
                ],
                const SizedBox(height: 16),
                _Section(
                  title: l10n.traineeProfileEmergency,
                  emptyText: l10n.traineeProfileNoEmergency,
                  rows: [
                    if (profile.emergencyContactName != null ||
                        profile.emergencyPhone != null)
                      _Row(
                        profile.emergencyContactName ?? '',
                        profile.emergencyPhone,
                      ),
                  ],
                ),
              ],
            ),
          ),
        );
  }

  static String? _birth(AppLocalizations l10n, TraineeProfile profile) {
    final date = profile.birthDate;
    if (date == null) return null;
    final formatted = DateFormat('dd.MM.y', 'pl').format(date);
    final age = profile.ageOn(DateTime.now());
    return age == null
        ? formatted
        : l10n.traineeProfileBirthWithAge(formatted, age);
  }

  static String _relation(AppLocalizations l10n, String code) => switch (code) {
    'PARENT' => l10n.traineeProfileRelationParent,
    _ => l10n.traineeProfileRelationOther,
  };
}

class _Row {
  const _Row(this.label, this.value);
  final String label;
  final String? value;
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.rows, this.emptyText});

  final String title;
  final String? emptyText;
  final List<_Row> rows;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 10,
          children: [
            Text(title, style: theme.textTheme.titleSmall),
            if (rows.isEmpty && emptyText != null) Text(emptyText!),
            for (final row in rows)
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    flex: 2,
                    child: Text(row.label, style: theme.textTheme.bodySmall),
                  ),
                  Expanded(
                    flex: 3,
                    child: SelectableText(
                      row.value ?? l10n.traineeProfileMissing,
                      style: row.value == null
                          ? theme.textTheme.bodyMedium?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            )
                          : theme.textTheme.bodyMedium,
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
